package com.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maintenance.common.BusinessException;
import com.maintenance.dto.PurchaseSuggestionDTO;
import com.maintenance.entity.PurchaseSuggestion;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.enums.EventType;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.PurchaseSuggestionMapper;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.SparePartOccupationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
    private final PurchaseSuggestionMapper purchaseSuggestionMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final LocalMessageQueue messageQueue;
    private final AuditService auditService;

    public SparePartService(SparePartMapper sparePartMapper,
                            SparePartOccupationMapper sparePartOccupationMapper,
                            PurchaseSuggestionMapper purchaseSuggestionMapper,
                            RedisTemplate<String, Object> redisTemplate,
                            LocalMessageQueue messageQueue,
                            AuditService auditService) {
        this.sparePartMapper = sparePartMapper;
        this.sparePartOccupationMapper = sparePartOccupationMapper;
        this.purchaseSuggestionMapper = purchaseSuggestionMapper;
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
     *
     * Fix: Uses retry logic for lock acquisition instead of silently skipping.
     * If lock cannot be acquired after retries, throws BusinessException to trigger
     * transactional rollback (preventing partial release inconsistency).
     */
    @Transactional
    public void releaseOccupationsByWorkOrder(Long workOrderId) {
        // 1. Find all OCCUPIED and PRE_RESERVED records for this work order
        List<SparePartOccupation> occupiedList = sparePartOccupationMapper
                .selectByWorkOrderAndStatus(workOrderId, OccupationStatus.OCCUPIED.name());
        List<SparePartOccupation> preReservedList = sparePartOccupationMapper
                .selectByWorkOrderAndStatus(workOrderId, OccupationStatus.PRE_RESERVED.name());

        List<SparePartOccupation> allToRelease = new java.util.ArrayList<>(occupiedList != null ? occupiedList : List.of());
        if (preReservedList != null) {
            allToRelease.addAll(preReservedList);
        }

        if (allToRelease.isEmpty()) {
            log.info("No occupied/pre-reserved spare parts to release for workOrder [{}]", workOrderId);
            return;
        }

        for (SparePartOccupation occupation : allToRelease) {
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
                "All occupations released, count=" + allToRelease.size());
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
     * Pre-reserve spare parts for a work order before dispatch.
     * Uses Redis lock and atomic stock decrease, same pattern as occupyPart.
     * Returns list of successfully pre-reserved occupations.
     */
    @Transactional
    public List<SparePartOccupation> preReserveParts(Long workOrderId, String equipmentType, int faultLevel) {
        List<SparePart> applicableParts = sparePartMapper.selectByEquipmentType(equipmentType);
        if (applicableParts == null || applicableParts.isEmpty()) {
            log.info("No applicable parts for equipment type [{}], skipping pre-reservation", equipmentType);
            return List.of();
        }

        List<SparePartOccupation> reserved = new java.util.ArrayList<>();
        for (SparePart part : applicableParts) {
            int requiredQty = estimateRequiredQuantity(faultLevel);
            String lockKey = PART_LOCK_PREFIX + part.getId();
            boolean locked = false;
            try {
                locked = tryLock(lockKey, 10);
                if (!locked) {
                    log.warn("Failed to acquire lock for pre-reservation, partId={}", part.getId());
                    publishPreReserveFailed(workOrderId, part, requiredQty, "Lock acquisition failed");
                    continue;
                }

                SparePart current = sparePartMapper.selectById(part.getId());
                if (current == null || current.getStockQuantity() < requiredQty) {
                    log.warn("Insufficient stock for pre-reservation: part={}, stock={}, required={}",
                            part.getPartCode(), current != null ? current.getStockQuantity() : 0, requiredQty);
                    publishPreReserveFailed(workOrderId, part, requiredQty, "Insufficient stock");
                    continue;
                }

                int rows = sparePartMapper.decreaseStock(part.getId(), requiredQty);
                if (rows == 0) {
                    log.warn("Stock decrease failed for pre-reservation (concurrent conflict), partId={}", part.getId());
                    publishPreReserveFailed(workOrderId, part, requiredQty, "Concurrent conflict");
                    continue;
                }

                SparePartOccupation occupation = new SparePartOccupation();
                occupation.setWorkOrderId(workOrderId);
                occupation.setPartId(part.getId());
                occupation.setQuantity(requiredQty);
                occupation.setStatus(OccupationStatus.PRE_RESERVED.name());
                occupation.setCreatedAt(LocalDateTime.now());
                sparePartOccupationMapper.insert(occupation);
                reserved.add(occupation);

                Map<String, Object> eventPayload = new HashMap<>();
                eventPayload.put("workOrderId", workOrderId);
                eventPayload.put("partId", part.getId());
                eventPayload.put("partCode", part.getPartCode());
                eventPayload.put("quantity", requiredQty);
                messageQueue.publish(EventType.PART_PRE_RESERVED.name(), eventPayload);

                log.info("Part pre-reserved: workOrder={}, part={}, qty={}", workOrderId, part.getPartCode(), requiredQty);
            } finally {
                if (locked) {
                    unlock(lockKey);
                }
            }
        }

        auditService.log("SPARE_PART", "PRE_RESERVE", "WorkOrder", workOrderId, "SYSTEM",
                "Parts pre-reserved: count=" + reserved.size() + "/" + applicableParts.size());
        return reserved;
    }

    /**
     * Release all PRE_RESERVED spare parts for a work order.
     */
    @Transactional
    public void releasePreReservationsByWorkOrder(Long workOrderId) {
        List<SparePartOccupation> preReservedList = sparePartOccupationMapper
                .selectByWorkOrderAndStatus(workOrderId, OccupationStatus.PRE_RESERVED.name());

        if (preReservedList == null || preReservedList.isEmpty()) {
            log.info("No pre-reserved spare parts to release for workOrder [{}]", workOrderId);
            return;
        }

        for (SparePartOccupation occupation : preReservedList) {
            String lockKey = PART_LOCK_PREFIX + occupation.getPartId();
            boolean locked = false;
            try {
                locked = tryLockWithRetry(lockKey, 10, LOCK_RETRY_MAX, LOCK_RETRY_DELAY_MS);
                if (!locked) {
                    throw new BusinessException(
                            "Failed to acquire part lock for pre-reservation release after " + LOCK_RETRY_MAX
                                    + " retries, partId=" + occupation.getPartId());
                }

                sparePartMapper.increaseStock(occupation.getPartId(), occupation.getQuantity());
                sparePartOccupationMapper.updateStatus(occupation.getId(),
                        OccupationStatus.RELEASED.name(), LocalDateTime.now());

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

        auditService.log("SPARE_PART", "RELEASE_PRE_RESERVED", "WorkOrder", workOrderId, "SYSTEM",
                "Pre-reserved parts released, count=" + preReservedList.size());
    }

    /**
     * Promote all PRE_RESERVED parts to OCCUPIED for a work order.
     * No stock change needed since stock was already decremented during pre-reservation.
     */
    @Transactional
    public void promotePreReservationsToOccupied(Long workOrderId) {
        int rows = sparePartOccupationMapper.batchUpdateStatus(
                workOrderId,
                OccupationStatus.PRE_RESERVED.name(),
                OccupationStatus.OCCUPIED.name(),
                LocalDateTime.now());
        log.info("Pre-reserved parts promoted to OCCUPIED for workOrder [{}], count={}", workOrderId, rows);

        auditService.log("SPARE_PART", "PROMOTE_PRE_RESERVED", "WorkOrder", workOrderId, "SYSTEM",
                "Pre-reserved promoted to occupied, count=" + rows);
    }

    /**
     * Generate purchase suggestions for parts that are insufficient for a work order.
     */
    @Transactional
    public List<PurchaseSuggestionDTO> generatePurchaseSuggestions(Long workOrderId, String equipmentType, int faultLevel) {
        List<SparePart> applicableParts = sparePartMapper.selectByEquipmentType(equipmentType);
        if (applicableParts == null || applicableParts.isEmpty()) {
            return List.of();
        }

        List<PurchaseSuggestionDTO> suggestions = new java.util.ArrayList<>();
        int requiredQty = estimateRequiredQuantity(faultLevel);

        for (SparePart part : applicableParts) {
            if (part.getStockQuantity() < requiredQty) {
                int shortfall = requiredQty - part.getStockQuantity();
                int suggestedQty = shortfall + (part.getMinStock() != null ? part.getMinStock() : 0);

                String urgency;
                if (faultLevel >= 4) {
                    urgency = "CRITICAL";
                } else if (faultLevel >= 3) {
                    urgency = "HIGH";
                } else {
                    urgency = "NORMAL";
                }

                PurchaseSuggestion suggestion = new PurchaseSuggestion();
                suggestion.setWorkOrderId(workOrderId);
                suggestion.setPartId(part.getId());
                suggestion.setSuggestedQuantity(suggestedQty);
                suggestion.setUrgencyLevel(urgency);
                suggestion.setCurrentStock(part.getStockQuantity());
                suggestion.setRequiredQuantity(requiredQty);
                suggestion.setStatus("PENDING");
                suggestion.setCreatedAt(LocalDateTime.now());
                suggestion.setUpdatedAt(LocalDateTime.now());
                purchaseSuggestionMapper.insert(suggestion);

                suggestions.add(PurchaseSuggestionDTO.builder()
                        .suggestionId(suggestion.getId())
                        .partId(part.getId())
                        .partCode(part.getPartCode())
                        .partName(part.getPartName())
                        .currentStock(part.getStockQuantity())
                        .requiredQuantity(requiredQty)
                        .suggestedPurchaseQuantity(suggestedQty)
                        .urgencyLevel(urgency)
                        .estimatedCost(part.getUnitPrice() != null
                                ? part.getUnitPrice().multiply(java.math.BigDecimal.valueOf(suggestedQty))
                                : java.math.BigDecimal.ZERO)
                        .build());

                Map<String, Object> eventPayload = new HashMap<>();
                eventPayload.put("workOrderId", workOrderId);
                eventPayload.put("partId", part.getId());
                eventPayload.put("partCode", part.getPartCode());
                eventPayload.put("suggestedQuantity", suggestedQty);
                eventPayload.put("urgency", urgency);
                messageQueue.publish(EventType.PURCHASE_SUGGESTION_CREATED.name(), eventPayload);
            }
        }

        if (!suggestions.isEmpty()) {
            auditService.log("SPARE_PART", "PURCHASE_SUGGESTION", "WorkOrder", workOrderId, "SYSTEM",
                    "Purchase suggestions generated: count=" + suggestions.size());
        }
        return suggestions;
    }

    /**
     * Estimate required quantity of each part based on fault level.
     */
    private int estimateRequiredQuantity(int faultLevel) {
        return switch (faultLevel) {
            case 4 -> 3;
            case 3 -> 2;
            default -> 1;
        };
    }

    private void publishPreReserveFailed(Long workOrderId, SparePart part, int requiredQty, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("workOrderId", workOrderId);
        payload.put("partId", part.getId());
        payload.put("partCode", part.getPartCode());
        payload.put("requiredQuantity", requiredQty);
        payload.put("currentStock", part.getStockQuantity());
        payload.put("reason", reason);
        messageQueue.publish(EventType.PART_PRE_RESERVE_FAILED.name(), payload);
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
