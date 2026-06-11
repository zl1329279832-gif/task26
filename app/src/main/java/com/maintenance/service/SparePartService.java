package com.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maintenance.common.BusinessException;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.enums.EventType;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.SparePartOccupationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SparePartService {

    private static final String PART_LOCK_PREFIX = "part:lock:";
    private static final int LOCK_RETRY_MAX = 3;
    private static final long LOCK_RETRY_DELAY_MS = 200;

    private final SparePartMapper sparePartMapper;
    private final SparePartOccupationMapper sparePartOccupationMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final LocalMessageQueue messageQueue;
    private final AuditService auditService;

    public SparePartService(SparePartMapper sparePartMapper,
                            SparePartOccupationMapper sparePartOccupationMapper,
                            RedisTemplate<String, Object> redisTemplate,
                            LocalMessageQueue messageQueue,
                            AuditService auditService) {
        this.sparePartMapper = sparePartMapper;
        this.sparePartOccupationMapper = sparePartOccupationMapper;
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
        String lockKey = PART_LOCK_PREFIX + partId;
        boolean locked = false;
        try {
            // 1. Acquire Redis distributed lock
            locked = tryLock(lockKey, 10);
            if (!locked) {
                throw new BusinessException("Failed to acquire part lock, partId=" + partId);
            }

            // FIX: Idempotent guard - check if this workOrder+partId is already occupied
            List<SparePartOccupation> existing = sparePartOccupationMapper.selectByWorkOrderAndStatus(
                    workOrderId, OccupationStatus.OCCUPIED.name());
            if (existing != null) {
                for (SparePartOccupation occ : existing) {
                    if (partId.equals(occ.getPartId())) {
                        log.warn("Part [{}] already occupied for workOrder [{}], returning existing occupation",
                                partId, workOrderId);
                        return occ;
                    }
                }
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

            // 5. Publish PART_REQUESTED event with deterministic eventId
            Map<String, Object> eventPayload = new HashMap<>();
            eventPayload.put("workOrderId", workOrderId);
            eventPayload.put("partId", partId);
            eventPayload.put("partCode", part.getPartCode());
            eventPayload.put("partName", part.getPartName());
            eventPayload.put("quantity", quantity);
            eventPayload.put("occupationId", occupation.getId());
            String eventId = "PART_REQUESTED:" + workOrderId + ":" + partId;
            messageQueue.publishWithId(eventId, EventType.PART_REQUESTED.name(), eventPayload);

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

        // FIX: Guard against consuming non-OCCUPIED records (already CONSUMED or RELEASED)
        if (!OccupationStatus.OCCUPIED.name().equals(occupation.getStatus())) {
            log.warn("Part occupation [{}] is not OCCUPIED (status={}), skipping consume",
                    occupationId, occupation.getStatus());
            return;
        }

        // Update status to CONSUMED, set consumed_at
        sparePartOccupationMapper.updateStatus(occupationId, OccupationStatus.CONSUMED.name(), LocalDateTime.now());
        log.info("Spare part consumed: occupationId={}, workOrder={}, partId={}",
                occupationId, occupation.getWorkOrderId(), occupation.getPartId());

        // Publish PART_CONSUMED event with deterministic eventId
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("occupationId", occupationId);
        eventPayload.put("workOrderId", occupation.getWorkOrderId());
        eventPayload.put("partId", occupation.getPartId());
        eventPayload.put("quantity", occupation.getQuantity());
        String eventId = "PART_CONSUMED:" + occupationId;
        messageQueue.publishWithId(eventId, EventType.PART_CONSUMED.name(), eventPayload);

        // Audit log
        auditService.log("SPARE_PART", "CONSUME", "WorkOrder", occupation.getWorkOrderId(), "SYSTEM",
                "Part consumed: occupationId=" + occupationId + ", qty=" + occupation.getQuantity());
    }

    /**
     * Release all OCCUPIED spare parts for a work order.
     * Returns occupied quantity back to stock for each part.
     *
     * Fix: Uses retry logic for lock acquisition instead of silently skipping.
     * If lock cannot be acquired after retries, throws BusinessException to trigger
     * transactional rollback (preventing partial release inconsistency).
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

        // FIX: Collect events and publish after ALL releases succeed.
        // If any release fails, the transaction rolls back and no events are published,
        // preventing orphaned events that don't match the actual DB state.
        List<Map<String, Object>> deferredEvents = new ArrayList<>();

        for (SparePartOccupation occupation : occupiedList) {
            String lockKey = PART_LOCK_PREFIX + occupation.getPartId();
            boolean locked = false;
            try {
                // FIX: Retry lock acquisition instead of silently skipping
                locked = tryLockWithRetry(lockKey, 10, LOCK_RETRY_MAX, LOCK_RETRY_DELAY_MS);
                if (!locked) {
                    // FIX: Throw exception instead of silently continuing
                    // This triggers @Transactional rollback, keeping all state consistent
                    throw new BusinessException(
                            "Failed to acquire part lock after " + LOCK_RETRY_MAX
                                    + " retries, partId=" + occupation.getPartId()
                                    + ", workOrderId=" + workOrderId);
                }

                // 3. Return stock via increaseStock
                sparePartMapper.increaseStock(occupation.getPartId(), occupation.getQuantity());

                // 4. Update occupation status to RELEASED
                sparePartOccupationMapper.updateStatus(occupation.getId(),
                        OccupationStatus.RELEASED.name(), LocalDateTime.now());
                log.info("Spare part released: occupationId={}, partId={}, qty={}",
                        occupation.getId(), occupation.getPartId(), occupation.getQuantity());

                // 5. Defer PART_RELEASED event (publish after all succeed)
                Map<String, Object> eventPayload = new HashMap<>();
                eventPayload.put("occupationId", occupation.getId());
                eventPayload.put("workOrderId", workOrderId);
                eventPayload.put("partId", occupation.getPartId());
                eventPayload.put("quantity", occupation.getQuantity());
                deferredEvents.add(eventPayload);
            } finally {
                if (locked) {
                    unlock(lockKey);
                }
            }
        }

        // 6. Publish all deferred PART_RELEASED events after all releases succeeded
        for (Map<String, Object> eventPayload : deferredEvents) {
            String eventId = "PART_RELEASED:" + workOrderId + ":" + eventPayload.get("partId");
            messageQueue.publishWithId(eventId, EventType.PART_RELEASED.name(), eventPayload);
        }

        // 7. Audit log
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
     * Try to acquire a lock with retry logic.
     * Retries up to maxRetries times with delayMs between attempts.
     */
    private boolean tryLockWithRetry(String key, long expireSeconds, int maxRetries, long delayMs) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (tryLock(key, expireSeconds)) {
                return true;
            }
            log.warn("Lock acquire attempt {}/{} failed for key [{}], retrying in {}ms",
                    attempt, maxRetries, key, delayMs);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during lock retry for key [{}]", key);
                return false;
            }
        }
        return false;
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
