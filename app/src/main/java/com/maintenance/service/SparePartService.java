package com.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maintenance.common.BusinessException;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.EventType;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.enums.WorkOrderStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.SparePartOccupationMapper;
import com.maintenance.mapper.WorkOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SparePartService {

    private static final String PART_LOCK_PREFIX = "part:lock:";

    private static final Set<String> ALLOWED_OCCUPY_STATUSES = Set.of(
            WorkOrderStatus.ACCEPTED.name(),
            WorkOrderStatus.ARRIVED.name(),
            WorkOrderStatus.REPAIRING.name()
    );

    private final SparePartMapper sparePartMapper;
    private final SparePartOccupationMapper sparePartOccupationMapper;
    private final WorkOrderMapper workOrderMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final LocalMessageQueue messageQueue;
    private final AuditService auditService;

    public SparePartService(SparePartMapper sparePartMapper,
                            SparePartOccupationMapper sparePartOccupationMapper,
                            WorkOrderMapper workOrderMapper,
                            RedisTemplate<String, Object> redisTemplate,
                            LocalMessageQueue messageQueue,
                            AuditService auditService) {
        this.sparePartMapper = sparePartMapper;
        this.sparePartOccupationMapper = sparePartOccupationMapper;
        this.workOrderMapper = workOrderMapper;
        this.redisTemplate = redisTemplate;
        this.messageQueue = messageQueue;
        this.auditService = auditService;
    }

    /**
     * Occupy spare parts for a work order.
     * Uses Redis distributed lock (SETNX with 10s expiry) to prevent concurrent stock issues.
     */
    @Transactional
    public SparePartOccupation occupyPart(Long workOrderId, Long partId, int quantity) {
        // Validate work order is in a state that allows part occupation
        WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
        if (workOrder == null) {
            throw new BusinessException("Work order not found, workOrderId=" + workOrderId);
        }
        if (!ALLOWED_OCCUPY_STATUSES.contains(workOrder.getStatus())) {
            throw new BusinessException("Cannot occupy parts: work order status=" + workOrder.getStatus()
                    + " (must be ACCEPTED/ARRIVED/REPAIRING)");
        }

        String lockKey = PART_LOCK_PREFIX + partId;
        boolean locked = false;
        try {
            // 1. Acquire Redis distributed lock
            locked = tryLock(lockKey, 10);
            if (!locked) {
                throw new BusinessException("Failed to acquire part lock, partId=" + partId);
            }

            // 2. Check stock availability
            SparePart part = sparePartMapper.selectById(partId);
            if (part == null) {
                throw new BusinessException("Spare part not found, partId=" + partId);
            }
            if (part.getStockQuantity() < quantity) {
                throw new BusinessException("Insufficient stock, partCode=" + part.getPartCode()
                        + ", stock=" + part.getStockQuantity() + ", required=" + quantity);
            }

            // 3. Decrease stock (SQL has stock_quantity >= quantity condition)
            int rows = sparePartMapper.decreaseStock(partId, quantity);
            if (rows == 0) {
                throw new BusinessException("Stock decrease failed (concurrent conflict), partId=" + partId);
            }

            // 4. Create occupation record (status=OCCUPIED)
            SparePartOccupation occupation = new SparePartOccupation();
            occupation.setWorkOrderId(workOrderId);
            occupation.setPartId(partId);
            occupation.setQuantity(quantity);
            occupation.setStatus(OccupationStatus.OCCUPIED.name());
            occupation.setCreatedAt(LocalDateTime.now());
            sparePartOccupationMapper.insert(occupation);
            log.info("Spare part occupied: workOrder={}, part={}, qty={}, id={}",
                    workOrderId, partId, quantity, occupation.getId());

            // 5. Publish PART_REQUESTED event
            Map<String, Object> eventPayload = new HashMap<>();
            eventPayload.put("workOrderId", workOrderId);
            eventPayload.put("partId", partId);
            eventPayload.put("partCode", part.getPartCode());
            eventPayload.put("partName", part.getPartName());
            eventPayload.put("quantity", quantity);
            eventPayload.put("occupationId", occupation.getId());
            messageQueue.publish(EventType.PART_REQUESTED.name(), eventPayload);

            // 6. Audit log
            auditService.log("SPARE_PART", "OCCUPY", "WorkOrder", workOrderId, "SYSTEM",
                    "Part occupied: partCode=" + part.getPartCode() + ", qty=" + quantity);

            return occupation;
        } finally {
            // 7. Release lock in finally block
            if (locked) {
                unlock(lockKey);
            }
        }
    }

    /**
     * Consume a single occupied part (mark as CONSUMED).
     */
    @Transactional
    public void consumePart(Long occupationId) {
        SparePartOccupation occupation = sparePartOccupationMapper.selectById(occupationId);
        if (occupation == null) {
            throw new BusinessException("Part occupation not found, id=" + occupationId);
        }

        // Update status to CONSUMED, set consumed_at
        sparePartOccupationMapper.updateStatus(occupationId, OccupationStatus.CONSUMED.name(), LocalDateTime.now());
        log.info("Spare part consumed: occupationId={}, workOrder={}, partId={}",
                occupationId, occupation.getWorkOrderId(), occupation.getPartId());

        // Publish PART_CONSUMED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("occupationId", occupationId);
        eventPayload.put("workOrderId", occupation.getWorkOrderId());
        eventPayload.put("partId", occupation.getPartId());
        eventPayload.put("quantity", occupation.getQuantity());
        messageQueue.publish(EventType.PART_CONSUMED.name(), eventPayload);

        // Audit log
        auditService.log("SPARE_PART", "CONSUME", "WorkOrder", occupation.getWorkOrderId(), "SYSTEM",
                "Part consumed: occupationId=" + occupationId + ", qty=" + occupation.getQuantity());
    }

    /**
     * Release all OCCUPIED spare parts for a work order.
     * Returns occupied quantity back to stock for each part.
     */
    @Transactional
    public void releaseOccupationsByWorkOrder(Long workOrderId) {
        // 1. Find all OCCUPIED records for this work order
        List<SparePartOccupation> occupiedList = sparePartOccupationMapper
                .selectByWorkOrderAndStatus(workOrderId, OccupationStatus.OCCUPIED.name());

        if (occupiedList == null || occupiedList.isEmpty()) {
            log.info("No occupied spare parts to release for workOrder [{}]", workOrderId);
            return;
        }

        for (SparePartOccupation occupation : occupiedList) {
            String lockKey = PART_LOCK_PREFIX + occupation.getPartId();
            boolean locked = false;
            try {
                // 2. Acquire lock for each part
                locked = tryLock(lockKey, 10);
                if (!locked) {
                    log.warn("Failed to acquire lock for part [{}], skipping", occupation.getPartId());
                    continue;
                }

                // 3. Return stock via increaseStock
                sparePartMapper.increaseStock(occupation.getPartId(), occupation.getQuantity());

                // 4. Update occupation status to RELEASED
                sparePartOccupationMapper.updateStatus(occupation.getId(),
                        OccupationStatus.RELEASED.name(), LocalDateTime.now());
                log.info("Spare part released: occupationId={}, partId={}, qty={}",
                        occupation.getId(), occupation.getPartId(), occupation.getQuantity());

                // 5. Publish PART_RELEASED event
                Map<String, Object> eventPayload = new HashMap<>();
                eventPayload.put("occupationId", occupation.getId());
                eventPayload.put("workOrderId", workOrderId);
                eventPayload.put("partId", occupation.getPartId());
                eventPayload.put("quantity", occupation.getQuantity());
                messageQueue.publish(EventType.PART_RELEASED.name(), eventPayload);
            } finally {
                if (locked) {
                    unlock(lockKey);
                }
            }
        }

        // 6. Audit log
        auditService.log("SPARE_PART", "RELEASE_ALL", "WorkOrder", workOrderId, "SYSTEM",
                "All occupations released, count=" + occupiedList.size());
    }

    /**
     * Consume all OCCUPIED spare parts for a work order (batch update to CONSUMED).
     */
    @Transactional
    public void consumeAllByWorkOrder(Long workOrderId) {
        int rows = sparePartOccupationMapper.batchUpdateStatus(
                workOrderId,
                OccupationStatus.OCCUPIED.name(),
                OccupationStatus.CONSUMED.name(),
                LocalDateTime.now());
        log.info("All occupied spare parts consumed for workOrder [{}], count={}", workOrderId, rows);
    }

    /**
     * Get all occupation records for a work order.
     */
    public List<SparePartOccupation> getOccupationsByWorkOrder(Long workOrderId) {
        return sparePartOccupationMapper.selectByWorkOrderId(workOrderId);
    }

    /**
     * Query spare parts with optional filters.
     */
    public List<SparePart> queryParts(String partType, String equipmentType, Boolean lowStockOnly) {
        if (Boolean.TRUE.equals(lowStockOnly)) {
            return sparePartMapper.selectLowStock();
        }
        if (StringUtils.hasText(equipmentType)) {
            return sparePartMapper.selectByEquipmentType(equipmentType);
        }
        LambdaQueryWrapper<SparePart> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(partType)) {
            wrapper.eq(SparePart::getPartType, partType);
        }
        return sparePartMapper.selectList(wrapper);
    }

    /**
     * Get a spare part by ID.
     */
    public SparePart getById(Long id) {
        return sparePartMapper.selectById(id);
    }

    /**
     * Try to acquire a Redis distributed lock using SETNX.
     */
    private boolean tryLock(String key, long expireSeconds) {
        try {
            Boolean result = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", expireSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Redis lock acquire failed for key [{}]: {}", key, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Release a Redis distributed lock.
     */
    private void unlock(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Redis lock release failed for key [{}]: {}", key, e.getMessage(), e);
        }
    }
}
