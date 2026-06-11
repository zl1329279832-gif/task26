package com.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maintenance.common.BusinessException;
import com.maintenance.dto.DispatchPlanResult;
import com.maintenance.dto.DispatchResult;
import com.maintenance.dto.FaultReportRequest;
import com.maintenance.dto.PreOccupyResult;
import com.maintenance.entity.DispatchPlan;
import com.maintenance.entity.Equipment;
import com.maintenance.entity.Fault;
import com.maintenance.entity.SlaRecord;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.EquipmentStatus;
import com.maintenance.enums.EventType;
import com.maintenance.enums.FaultStatus;
import com.maintenance.enums.WorkOrderStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.EquipmentMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.WorkOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class FaultService {

    private static final DateTimeFormatter CODE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Random RANDOM = new Random();
    private static final String FAULT_REPORT_LOCK_PREFIX = "fault:report:lock:";

    private final FaultMapper faultMapper;
    private final EquipmentMapper equipmentMapper;
    private final WorkOrderMapper workOrderMapper;
    private final SparePartMapper sparePartMapper;
    private final LocalMessageQueue messageQueue;
    private final AutoDispatchService autoDispatchService;
    private final AuditService auditService;
    private final DowntimeService downtimeService;
    private final PredictiveDispatchService predictiveDispatchService;
    private final SlaService slaService;
    private final RedisTemplate<String, Object> redisTemplate;

    public FaultService(FaultMapper faultMapper,
                        EquipmentMapper equipmentMapper,
                        WorkOrderMapper workOrderMapper,
                        SparePartMapper sparePartMapper,
                        LocalMessageQueue messageQueue,
                        AutoDispatchService autoDispatchService,
                        AuditService auditService,
                        DowntimeService downtimeService,
                        @Lazy PredictiveDispatchService predictiveDispatchService,
                        SlaService slaService,
                        RedisTemplate<String, Object> redisTemplate) {
        this.faultMapper = faultMapper;
        this.equipmentMapper = equipmentMapper;
        this.workOrderMapper = workOrderMapper;
        this.sparePartMapper = sparePartMapper;
        this.messageQueue = messageQueue;
        this.autoDispatchService = autoDispatchService;
        this.auditService = auditService;
        this.downtimeService = downtimeService;
        this.predictiveDispatchService = predictiveDispatchService;
        this.slaService = slaService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Report a fault (core method).
     * Integrates predictive dispatch: generates multiple plans, pre-occupies parts,
     * creates SLA records, and handles purchase suggestions for shortage parts.
     */
    @Transactional
    public Fault reportFault(FaultReportRequest request) {
        // Validate equipment
        Equipment equipment = equipmentMapper.selectById(request.getEquipmentId());
        if (equipment == null) {
            throw new BusinessException("Equipment not found, equipmentId=" + request.getEquipmentId());
        }

        // FIX: Use Redis lock to make duplicate fault detection atomic.
        // Without this lock, two concurrent reports for the same equipment+level
        // could both pass the duplicate check and create two faults/work orders.
        String lockKey = FAULT_REPORT_LOCK_PREFIX + request.getEquipmentId() + ":" + request.getFaultLevel();
        boolean locked = false;
        try {
            locked = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS));
            if (!locked) {
                throw new BusinessException("Concurrent fault report in progress for equipmentId="
                        + request.getEquipmentId() + ", faultLevel=" + request.getFaultLevel());
            }

            return doReportFault(request, equipment);
        } finally {
            if (locked) {
                try { redisTemplate.delete(lockKey); } catch (Exception e) {
                    log.error("Failed to release fault report lock [{}]: {}", lockKey, e.getMessage());
                }
            }
        }
    }

    /**
     * Internal method that performs the actual fault reporting logic.
     * Must be called within a Redis lock to prevent concurrent duplicate detection race.
     */
    private Fault doReportFault(FaultReportRequest request, Equipment equipment) {
        // 1. Duplicate fault detection: check for same equipment + same fault_level within 5 minutes
        List<Fault> recentFaults = faultMapper.selectRecentByEquipment(request.getEquipmentId(), 5);
        for (Fault recentFault : recentFaults) {
            if (recentFault.getFaultLevel().equals(request.getFaultLevel())) {
                String appendDesc = "[Duplicate report at " + LocalDateTime.now()
                        + "] " + request.getFaultDescription();
                faultMapper.incrementOccurrenceCount(recentFault.getId(), appendDesc);
                log.info("Duplicate fault detected for equipment [{}], existing faultId={}, occurrenceCount incremented",
                        request.getEquipmentId(), recentFault.getId());
                return faultMapper.selectById(recentFault.getId());
            }
        }

        // 2. Create fault record
        Fault fault = new Fault();
        fault.setFaultCode(generateFaultCode());
        fault.setEquipmentId(request.getEquipmentId());
        fault.setEquipmentType(equipment.getEquipmentType());
        fault.setFaultLevel(request.getFaultLevel());
        fault.setFaultDescription(request.getFaultDescription());
        fault.setReporter(request.getReporter());
        fault.setReporterPhone(request.getReporterPhone());
        fault.setStatus(FaultStatus.PENDING.name());
        fault.setOccurrenceCount(1);
        fault.setCreatedAt(LocalDateTime.now());
        fault.setUpdatedAt(LocalDateTime.now());
        faultMapper.insert(fault);
        log.info("Fault reported: faultCode={}, equipmentId={}, level={}",
                fault.getFaultCode(), request.getEquipmentId(), request.getFaultLevel());

        // 3. Update equipment status to FAULT
        equipmentMapper.updateStatus(request.getEquipmentId(), EquipmentStatus.FAULT.name());
        log.info("Equipment [{}] status updated to FAULT", request.getEquipmentId());

        // 4. Create work order
        WorkOrder workOrder = new WorkOrder();
        workOrder.setOrderCode(generateOrderCode());
        workOrder.setFaultId(fault.getId());
        workOrder.setEquipmentId(request.getEquipmentId());
        workOrder.setStatus(WorkOrderStatus.CREATED.name());
        workOrder.setPriority(request.getFaultLevel());
        workOrder.setFaultDescription(request.getFaultDescription());
        workOrder.setReassignCount(0);
        workOrder.setEscalateCount(0);

        WorkOrder recentCompletedOrder = workOrderMapper.selectLatestByEquipment(
                request.getEquipmentId(), WorkOrderStatus.COMPLETED.name());
        if (recentCompletedOrder != null && recentCompletedOrder.getCompletedAt() != null) {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
            if (recentCompletedOrder.getCompletedAt().isAfter(cutoff)) {
                workOrder.setIsRerepair(1);
                workOrder.setOriginalOrderId(recentCompletedOrder.getId());
                log.info("Re-repair detected for equipment [{}], original orderId={}",
                        request.getEquipmentId(), recentCompletedOrder.getId());
            }
        }
        if (workOrder.getIsRerepair() == null) {
            workOrder.setIsRerepair(0);
        }

        workOrder.setCreatedAt(LocalDateTime.now());
        workOrder.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.insert(workOrder);
        log.info("Work order created: orderCode={}, faultId={}, priority={}",
                workOrder.getOrderCode(), fault.getId(), workOrder.getPriority());

        // 5. Create SLA record
        SlaRecord slaRecord = slaService.createSlaRecord(workOrder.getId(), request.getFaultLevel());

        // 6. Generate multiple dispatch plans
        List<DispatchPlan> plans = predictiveDispatchService.generateDispatchPlans(
                workOrder, fault, equipment);

        // 7. Pre-occupy spare parts
        PreOccupyResult preOccupyResult = predictiveDispatchService.preOccupyParts(
                workOrder.getId(), equipment.getEquipmentType(), request.getFaultLevel());

        boolean partsAvailable = preOccupyResult.isAllPartsAvailable();

        // If parts insufficient and non-emergency, pause SLA
        if (!partsAvailable && request.getFaultLevel() < 3) {
            slaService.pauseSla(workOrder.getId(),
                    "备件不足,等待采购: " + preOccupyResult.getShortageParts().size() + "种备件缺货");
            slaRecord = slaService.getByWorkOrderId(workOrder.getId());
            log.info("SLA paused for workOrder [{}] due to parts shortage", workOrder.getId());
        }

        // 8. Execute dispatch
        DispatchResult dispatchResult;
        boolean downtimeStarted = false;

        if (plans.isEmpty()) {
            dispatchResult = DispatchResult.fail("No qualified technician available");
            // Release pre-occupied parts since no dispatch possible
            predictiveDispatchService.releasePreOccupiedParts(workOrder.getId());
        } else if (!partsAvailable && request.getFaultLevel() < 3) {
            // Non-emergency with insufficient parts: defer dispatch, release parts
            predictiveDispatchService.releasePreOccupiedParts(workOrder.getId());
            dispatchResult = DispatchResult.fail(
                    "Dispatch deferred: insufficient spare parts, purchase suggestion created");
        } else {
            // Select best plan and execute
            int selectedIndex = plans.get(0).getPlanIndex();
            try {
                dispatchResult = predictiveDispatchService.selectAndExecutePlan(
                        workOrder.getId(), selectedIndex);
            } catch (BusinessException e) {
                log.warn("Best plan execution failed: {}, trying fallback", e.getMessage());
                // Try next plans
                dispatchResult = DispatchResult.fail(e.getMessage());
                for (int i = 1; i < plans.size(); i++) {
                    try {
                        dispatchResult = predictiveDispatchService.selectAndExecutePlan(
                                workOrder.getId(), plans.get(i).getPlanIndex());
                        if (dispatchResult.isSuccess()) break;
                    } catch (BusinessException ex) {
                        log.warn("Fallback plan {} also failed: {}", plans.get(i).getPlanIndex(), ex.getMessage());
                    }
                }
            }

            if (dispatchResult.isSuccess()) {
                com.maintenance.entity.DowntimeRecord downtimeRecord = downtimeService.startDowntime(
                        request.getEquipmentId(), workOrder.getId(), fault.getId());
                downtimeStarted = true;
                log.info("Downtime started for equipment [{}], downtimeRecordId={}, workOrderId={}",
                        request.getEquipmentId(), downtimeRecord.getId(), workOrder.getId());
            } else {
                // All plans failed, release pre-occupied parts
                predictiveDispatchService.releasePreOccupiedParts(workOrder.getId());
                log.warn("All dispatch plans failed for workOrder [{}], pre-occupied parts released",
                        workOrder.getId());
            }
        }

        // 9. Publish FAULT_REPORTED event with deterministic eventId for idempotency
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("faultId", fault.getId());
        eventPayload.put("faultCode", fault.getFaultCode());
        eventPayload.put("equipmentId", request.getEquipmentId());
        eventPayload.put("faultLevel", request.getFaultLevel());
        eventPayload.put("workOrderId", workOrder.getId());
        eventPayload.put("orderCode", workOrder.getOrderCode());
        eventPayload.put("faultDescription", request.getFaultDescription());
        eventPayload.put("dispatchSuccess", dispatchResult.isSuccess());
        eventPayload.put("partsAvailable", partsAvailable);
        eventPayload.put("downtimeStarted", downtimeStarted);
        eventPayload.put("planCount", plans.size());
        eventPayload.put("slaDeadline", slaRecord != null ? slaRecord.getSlaDeadline().toString() : null);
        eventPayload.put("purchaseSuggestionCount",
                preOccupyResult.getShortageParts() != null ? preOccupyResult.getShortageParts().size() : 0);
        // FIX: Use deterministic eventId based on faultId to prevent duplicate event processing
        String eventId = "FAULT_REPORTED:" + fault.getId();
        messageQueue.publishWithId(eventId, EventType.FAULT_REPORTED.name(), eventPayload);

        // 10. Audit log
        auditService.log("FAULT", "REPORT", "Fault", fault.getId(), request.getReporter(),
                "Fault reported: code=" + fault.getFaultCode()
                        + ", equipment=" + equipment.getEquipmentName()
                        + ", level=" + request.getFaultLevel()
                        + ", plans=" + plans.size()
                        + ", dispatchResult=" + dispatchResult.getMessage()
                        + ", partsAvailable=" + partsAvailable
                        + ", slaDeadline=" + (slaRecord != null ? slaRecord.getSlaDeadline() : "N/A"));

        return fault;
    }

    /**
     * Get a fault by ID.
     */
    public Fault getById(Long id) {
        return faultMapper.selectById(id);
    }

    /**
     * Get all faults for an equipment.
     */
    public List<Fault> getByEquipment(Long equipmentId) {
        LambdaQueryWrapper<Fault> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Fault::getEquipmentId, equipmentId);
        wrapper.orderByDesc(Fault::getCreatedAt);
        return faultMapper.selectList(wrapper);
    }

    /**
     * Get faults by status.
     */
    public List<Fault> getByStatus(String status) {
        LambdaQueryWrapper<Fault> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Fault::getStatus, status);
        wrapper.orderByDesc(Fault::getCreatedAt);
        return faultMapper.selectList(wrapper);
    }

    /**
     * Generate fault code: "F" + yyyyMMddHHmmss + 4-digit random number
     */
    private String generateFaultCode() {
        String timestamp = LocalDateTime.now().format(CODE_FORMATTER);
        int randomNum = RANDOM.nextInt(10000);
        return "F" + timestamp + String.format("%04d", randomNum);
    }

    /**
     * Generate order code: "WO" + yyyyMMddHHmmss + 4-digit random number
     */
    private String generateOrderCode() {
        String timestamp = LocalDateTime.now().format(CODE_FORMATTER);
        int randomNum = RANDOM.nextInt(10000);
        return "WO" + timestamp + String.format("%04d", randomNum);
    }
}
