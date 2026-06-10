package com.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maintenance.common.BusinessException;
import com.maintenance.dto.DispatchResult;
import com.maintenance.dto.FaultReportRequest;
import com.maintenance.dto.PredictiveDispatchResult;
import com.maintenance.entity.DowntimeRecord;
import com.maintenance.entity.Equipment;
import com.maintenance.entity.Fault;
import com.maintenance.entity.SparePart;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
@Service
public class FaultService {

    private static final DateTimeFormatter CODE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Random RANDOM = new Random();

    private final FaultMapper faultMapper;
    private final EquipmentMapper equipmentMapper;
    private final WorkOrderMapper workOrderMapper;
    private final SparePartMapper sparePartMapper;
    private final LocalMessageQueue messageQueue;
    private final AutoDispatchService autoDispatchService;
    private final PredictiveDispatchService predictiveDispatchService;
    private final AuditService auditService;
    private final DowntimeService downtimeService;

    public FaultService(FaultMapper faultMapper,
                        EquipmentMapper equipmentMapper,
                        WorkOrderMapper workOrderMapper,
                        SparePartMapper sparePartMapper,
                        LocalMessageQueue messageQueue,
                        AutoDispatchService autoDispatchService,
                        PredictiveDispatchService predictiveDispatchService,
                        AuditService auditService,
                        DowntimeService downtimeService) {
        this.faultMapper = faultMapper;
        this.equipmentMapper = equipmentMapper;
        this.workOrderMapper = workOrderMapper;
        this.sparePartMapper = sparePartMapper;
        this.messageQueue = messageQueue;
        this.autoDispatchService = autoDispatchService;
        this.predictiveDispatchService = predictiveDispatchService;
        this.auditService = auditService;
        this.downtimeService = downtimeService;
    }

    /**
     * Report a fault (core method).
     * Handles duplicate detection, fault creation, work order creation, and auto-dispatch.
     *
     * Fixes applied:
     * 1. Check spare part availability before dispatching. If parts are insufficient,
     *    the work order is created but dispatch is deferred (equipment stays in FAULT status,
     *    no downtime record is started until a technician is assigned).
     * 2. Only start downtime AFTER successful dispatch (prevents orphaned downtime records
     *    when no technician is available).
     * 3. Handle dispatch failure gracefully: log, audit, and publish event with
     *    dispatchSuccess=false so supervisors are notified.
     */
    @Transactional
    public PredictiveDispatchResult reportFault(FaultReportRequest request) {
        // Validate equipment
        Equipment equipment = equipmentMapper.selectById(request.getEquipmentId());
        if (equipment == null) {
            throw new BusinessException("Equipment not found, equipmentId=" + request.getEquipmentId());
        }

        // 1. Duplicate fault detection: check for same equipment + same fault_level within 5 minutes
        List<Fault> recentFaults = faultMapper.selectRecentByEquipment(request.getEquipmentId(), 5);
        for (Fault recentFault : recentFaults) {
            if (recentFault.getFaultLevel().equals(request.getFaultLevel())) {
                String appendDesc = "[Duplicate report at " + LocalDateTime.now()
                        + "] " + request.getFaultDescription();
                faultMapper.incrementOccurrenceCount(recentFault.getId(), appendDesc);
                log.info("Duplicate fault detected for equipment [{}], existing faultId={}, occurrenceCount incremented",
                        request.getEquipmentId(), recentFault.getId());
                Fault existingFault = faultMapper.selectById(recentFault.getId());
                return PredictiveDispatchResult.builder()
                        .success(true)
                        .message("重复故障已合并, faultId=" + existingFault.getId())
                        .build();
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

        // Detect re-repair
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

        // 5. Generate predictive dispatch plans (replaces direct auto-dispatch)
        PredictiveDispatchResult result = predictiveDispatchService.generateDispatchPlans(
                workOrder, fault, equipment);

        // 6. Publish FAULT_REPORTED event with enriched payload
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("faultId", fault.getId());
        eventPayload.put("faultCode", fault.getFaultCode());
        eventPayload.put("equipmentId", request.getEquipmentId());
        eventPayload.put("faultLevel", request.getFaultLevel());
        eventPayload.put("workOrderId", workOrder.getId());
        eventPayload.put("orderCode", workOrder.getOrderCode());
        eventPayload.put("faultDescription", request.getFaultDescription());
        eventPayload.put("plansGenerated", result.isSuccess());
        eventPayload.put("planCount", result.getPlans() != null ? result.getPlans().size() : 0);
        eventPayload.put("partsPreReserved", result.isPartsPreReserved());
        eventPayload.put("slaPaused", result.isSlaPaused());
        eventPayload.put("dispatchSuccess", result.getDispatchResult() != null && result.getDispatchResult().isSuccess());
        messageQueue.publish(EventType.FAULT_REPORTED.name(), eventPayload);

        // 7. Audit log
        auditService.log("FAULT", "REPORT", "Fault", fault.getId(), request.getReporter(),
                "Fault reported: code=" + fault.getFaultCode()
                        + ", equipment=" + equipment.getEquipmentName()
                        + ", level=" + request.getFaultLevel()
                        + ", plans=" + (result.getPlans() != null ? result.getPlans().size() : 0));

        return result;
    }

    /**
     * Check if spare parts are available for a given equipment type.
     * Returns true if at least one applicable part has stock > 0, or if no parts
     * are configured for this equipment type (some repairs don't need parts).
     */
    private boolean checkSparePartAvailability(String equipmentType) {
        List<SparePart> applicableParts = sparePartMapper.selectByEquipmentType(equipmentType);
        if (applicableParts == null || applicableParts.isEmpty()) {
            // No parts configured for this equipment type - assume no parts needed
            return true;
        }

        // Check if at least one part has stock available
        for (SparePart part : applicableParts) {
            if (part.getStockQuantity() > 0) {
                return true;
            }
        }

        // All applicable parts are out of stock
        log.warn("All applicable spare parts out of stock for equipment type [{}]", equipmentType);
        return false;
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
