package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.entity.SlaRecord;
import com.maintenance.enums.EventType;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.SlaRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class SlaService {

    /** SLA deadlines by fault level (in minutes): L1=480, L2=240, L3=120, L4=60 */
    private static final int[] SLA_MINUTES_BY_LEVEL = {0, 480, 240, 120, 60};

    private final SlaRecordMapper slaRecordMapper;
    private final LocalMessageQueue messageQueue;
    private final AuditService auditService;

    public SlaService(SlaRecordMapper slaRecordMapper,
                      LocalMessageQueue messageQueue,
                      AuditService auditService) {
        this.slaRecordMapper = slaRecordMapper;
        this.messageQueue = messageQueue;
        this.auditService = auditService;
    }

    /**
     * Create an SLA record for a work order based on fault level.
     */
    @Transactional
    public SlaRecord createSlaRecord(Long workOrderId, int faultLevel) {
        // Check if SLA record already exists
        SlaRecord existing = slaRecordMapper.selectByWorkOrderId(workOrderId);
        if (existing != null) {
            log.warn("SLA record already exists for workOrder [{}]", workOrderId);
            return existing;
        }

        int slaMinutes = getSlaMinutes(faultLevel);
        SlaRecord record = new SlaRecord();
        record.setWorkOrderId(workOrderId);
        record.setFaultLevel(faultLevel);
        record.setSlaDeadline(LocalDateTime.now().plusMinutes(slaMinutes));
        record.setRemainingMinutes(slaMinutes);
        record.setStatus("ACTIVE");
        record.setCreatedAt(LocalDateTime.now());

        slaRecordMapper.insert(record);
        log.info("SLA record created for workOrder [{}], faultLevel={}, deadline={}, minutes={}",
                workOrderId, faultLevel, record.getSlaDeadline(), slaMinutes);

        auditService.log("SLA", "CREATE", "WorkOrder", workOrderId, "SYSTEM",
                "SLA created: faultLevel=" + faultLevel + ", deadline=" + record.getSlaDeadline()
                        + ", minutes=" + slaMinutes);

        return record;
    }

    /**
     * Pause SLA: save remaining time and mark as PAUSED.
     *
     * FIX: Guard against concurrent pause calls and already-paused records.
     */
    @Transactional
    public SlaRecord pauseSla(Long workOrderId, String reason) {
        SlaRecord record = slaRecordMapper.selectActiveByWorkOrder(workOrderId);
        if (record == null) {
            // FIX: Also check if already paused to make this idempotent
            SlaRecord existing = slaRecordMapper.selectByWorkOrderId(workOrderId);
            if (existing != null && "PAUSED".equals(existing.getStatus())) {
                log.info("SLA already paused for workOrder [{}], skipping duplicate pause", workOrderId);
                return existing;
            }
            log.warn("No active SLA record found for workOrder [{}], cannot pause", workOrderId);
            return null;
        }

        // Calculate remaining minutes
        Duration remaining = Duration.between(LocalDateTime.now(), record.getSlaDeadline());
        int remainingMinutes = (int) remaining.toMinutes();
        if (remainingMinutes < 0) {
            remainingMinutes = 0;
        }

        slaRecordMapper.updateSla(record.getId(), "PAUSED", remainingMinutes,
                LocalDateTime.now(), null, record.getSlaDeadline());

        record.setStatus("PAUSED");
        record.setRemainingMinutes(remainingMinutes);
        record.setPauseReason(reason);
        record.setPausedAt(LocalDateTime.now());

        log.info("SLA paused for workOrder [{}], remaining={}min, reason={}",
                workOrderId, remainingMinutes, reason);

        // Publish event with deterministic eventId
        Map<String, Object> payload = new HashMap<>();
        payload.put("workOrderId", workOrderId);
        payload.put("remainingMinutes", remainingMinutes);
        payload.put("reason", reason);
        String eventId = "SLA_PAUSED:" + workOrderId;
        messageQueue.publishWithId(eventId, EventType.SLA_PAUSED.name(), payload);

        auditService.log("SLA", "PAUSE", "WorkOrder", workOrderId, "SYSTEM",
                "SLA paused: remaining=" + remainingMinutes + "min, reason=" + reason);

        return record;
    }

    /**
     * Resume SLA: recalculate deadline from remaining time.
     *
     * FIX: Guard against concurrent resume calls and already-active records.
     */
    @Transactional
    public SlaRecord resumeSla(Long workOrderId) {
        SlaRecord record = slaRecordMapper.selectByWorkOrderId(workOrderId);
        if (record == null) {
            log.warn("No SLA record found for workOrder [{}], cannot resume", workOrderId);
            return null;
        }

        if (!"PAUSED".equals(record.getStatus())) {
            log.warn("SLA record for workOrder [{}] is not paused (status={}), cannot resume",
                    workOrderId, record.getStatus());
            return record;
        }

        int remainingMinutes = record.getRemainingMinutes() != null ? record.getRemainingMinutes() : 0;
        LocalDateTime newDeadline = LocalDateTime.now().plusMinutes(remainingMinutes);

        slaRecordMapper.updateSla(record.getId(), "ACTIVE", remainingMinutes,
                record.getPausedAt(), LocalDateTime.now(), newDeadline);

        record.setStatus("ACTIVE");
        record.setSlaDeadline(newDeadline);
        record.setResumedAt(LocalDateTime.now());

        log.info("SLA resumed for workOrder [{}], new deadline={}, remaining={}min",
                workOrderId, newDeadline, remainingMinutes);

        // Publish event with deterministic eventId
        Map<String, Object> payload = new HashMap<>();
        payload.put("workOrderId", workOrderId);
        payload.put("newDeadline", newDeadline.toString());
        payload.put("remainingMinutes", remainingMinutes);
        String eventId = "SLA_RESUMED:" + workOrderId;
        messageQueue.publishWithId(eventId, EventType.SLA_RESUMED.name(), payload);

        auditService.log("SLA", "RESUME", "WorkOrder", workOrderId, "SYSTEM",
                "SLA resumed: newDeadline=" + newDeadline + ", remaining=" + remainingMinutes + "min");

        return record;
    }

    /**
     * Mark SLA as MET or EXPIRED based on current time vs deadline.
     */
    @Transactional
    public SlaRecord finalizeSla(Long workOrderId) {
        SlaRecord record = slaRecordMapper.selectByWorkOrderId(workOrderId);
        if (record == null) {
            return null;
        }

        if ("MET".equals(record.getStatus()) || "EXPIRED".equals(record.getStatus())) {
            return record;
        }

        // If paused, resume first for calculation
        if ("PAUSED".equals(record.getStatus())) {
            int remaining = record.getRemainingMinutes() != null ? record.getRemainingMinutes() : 0;
            if (remaining > 0) {
                slaRecordMapper.updateSla(record.getId(), "MET", remaining,
                        record.getPausedAt(), LocalDateTime.now(), record.getSlaDeadline());
                record.setStatus("MET");
            } else {
                slaRecordMapper.updateStatus(record.getId(), "EXPIRED");
                record.setStatus("EXPIRED");
            }
        } else if ("ACTIVE".equals(record.getStatus())) {
            if (LocalDateTime.now().isBefore(record.getSlaDeadline())) {
                slaRecordMapper.updateStatus(record.getId(), "MET");
                record.setStatus("MET");
            } else {
                slaRecordMapper.updateStatus(record.getId(), "EXPIRED");
                record.setStatus("EXPIRED");
            }
        }

        log.info("SLA finalized for workOrder [{}], status={}", workOrderId, record.getStatus());

        auditService.log("SLA", "FINALIZE", "WorkOrder", workOrderId, "SYSTEM",
                "SLA finalized: status=" + record.getStatus());

        return record;
    }

    /**
     * Get SLA record for a work order.
     */
    public SlaRecord getByWorkOrderId(Long workOrderId) {
        return slaRecordMapper.selectByWorkOrderId(workOrderId);
    }

    /**
     * Calculate SLA score (0-15 points) based on remaining time ratio.
     * Less remaining time → higher urgency score.
     */
    public int calculateSlaScore(Long workOrderId) {
        SlaRecord record = slaRecordMapper.selectActiveByWorkOrder(workOrderId);
        if (record == null) {
            return 0;
        }

        int totalMinutes = getSlaMinutes(record.getFaultLevel());
        Duration remaining = Duration.between(LocalDateTime.now(), record.getSlaDeadline());
        int remainingMinutes = (int) remaining.toMinutes();
        if (remainingMinutes < 0) {
            return 15; // Expired - maximum urgency
        }

        double ratio = (double) remainingMinutes / totalMinutes;
        if (ratio <= 0.1) {
            return 15; // Less than 10% remaining
        } else if (ratio <= 0.25) {
            return 12;
        } else if (ratio <= 0.5) {
            return 8;
        } else if (ratio <= 0.75) {
            return 4;
        } else {
            return 2;
        }
    }

    /**
     * Get SLA minutes for a fault level.
     */
    public int getSlaMinutes(int faultLevel) {
        if (faultLevel >= 1 && faultLevel <= 4) {
            return SLA_MINUTES_BY_LEVEL[faultLevel];
        }
        return SLA_MINUTES_BY_LEVEL[1]; // Default to L1
    }
}
