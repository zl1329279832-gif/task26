package com.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maintenance.common.BusinessException;
import com.maintenance.dto.DispatchResult;
import com.maintenance.dto.FaultReportRequest;
import com.maintenance.entity.DowntimeRecord;
import com.maintenance.entity.Equipment;
import com.maintenance.entity.Fault;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.EquipmentStatus;
import com.maintenance.enums.EventType;
import com.maintenance.enums.FaultStatus;
import com.maintenance.enums.WorkOrderStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.EquipmentMapper;
import com.maintenance.mapper.FaultMapper;
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
    private final LocalMessageQueue messageQueue;
    private final AutoDispatchService autoDispatchService;
    private final AuditService auditService;
    private final DowntimeService downtimeService;

    public FaultService(FaultMapper faultMapper,
                        EquipmentMapper equipmentMapper,
                        WorkOrderMapper workOrderMapper,
                        LocalMessageQueue messageQueue,
                        AutoDispatchService autoDispatchService,
                        AuditService auditService,
                        DowntimeService downtimeService) {
        this.faultMapper = faultMapper;
        this.equipmentMapper = equipmentMapper;
        this.workOrderMapper = workOrderMapper;
        this.messageQueue = messageQueue;
        this.autoDispatchService = autoDispatchService;
        this.auditService = auditService;
        this.downtimeService = downtimeService;
    }

    /**
     * Report a fault (core method).
     * Handles duplicate detection, fault creation, work order creation, and auto-dispatch.
     */
    @Transactional
    public Fault reportFault(FaultReportRequest request) {
        // Validate equipment
        Equipment equipment = equipmentMapper.selectById(request.getEquipmentId());
        if (equipment == null) {
            throw new BusinessException("Equipment not found, equipmentId=" + request.getEquipmentId());
        }

        // 1. Duplicate fault detection: check for same equipment + same fault_level within 5 minutes
        List<Fault> recentFaults = faultMapper.selectRecentByEquipment(request.getEquipmentId(), 5);
        for (Fault recentFault : recentFaults) {
            if (recentFault.getFaultLevel().equals(request.getFaultLevel())) {
                // Duplicate found - increment occurrence count and append description
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

        // 4. Create work order first (before starting downtime, so we have the workOrderId)
        WorkOrder workOrder = new WorkOrder();
        workOrder.setOrderCode(generateOrderCode());
        workOrder.setFaultId(fault.getId());
        workOrder.setEquipmentId(request.getEquipmentId());
        workOrder.setStatus(WorkOrderStatus.CREATED.name());
        workOrder.setPriority(request.getFaultLevel());
        workOrder.setFaultDescription(request.getFaultDescription());
        workOrder.setReassignCount(0);
        workOrder.setEscalateCount(0);

        // Detect re-repair: check if equipment had a COMPLETED order within 24 hours
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

        // 5. Create downtime record (now with workOrderId available)
        DowntimeRecord downtimeRecord = downtimeService.startDowntime(
                request.getEquipmentId(), workOrder.getId(), fault.getId());
        log.info("Downtime started for equipment [{}], downtimeRecordId={}, workOrderId={}",
                request.getEquipmentId(), downtimeRecord.getId(), workOrder.getId());

        // 6. Dispatch based on fault level
        DispatchResult dispatchResult;
        if (request.getFaultLevel() >= 3) {
            // Emergency: fault_level >= 3 (SERIOUS or CRITICAL)
            dispatchResult = autoDispatchService.emergencyDispatch(workOrder, fault);
            log.info("Emergency dispatch attempted for workOrder [{}], result={}",
                    workOrder.getId(), dispatchResult.getMessage());
        } else {
            // Normal dispatch
            dispatchResult = autoDispatchService.autoDispatch(workOrder, fault);
            log.info("Auto dispatch attempted for workOrder [{}], result={}",
                    workOrder.getId(), dispatchResult.getMessage());
        }

        // 7. Publish FAULT_REPORTED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("faultId", fault.getId());
        eventPayload.put("faultCode", fault.getFaultCode());
        eventPayload.put("equipmentId", request.getEquipmentId());
        eventPayload.put("faultLevel", request.getFaultLevel());
        eventPayload.put("workOrderId", workOrder.getId());
        eventPayload.put("orderCode", workOrder.getOrderCode());
        eventPayload.put("faultDescription", request.getFaultDescription());
        eventPayload.put("dispatchSuccess", dispatchResult.isSuccess());
        messageQueue.publish(EventType.FAULT_REPORTED.name(), eventPayload);

        // 8. Audit log
        auditService.log("FAULT", "REPORT", "Fault", fault.getId(), request.getReporter(),
                "Fault reported: code=" + fault.getFaultCode()
                        + ", equipment=" + equipment.getEquipmentName()
                        + ", level=" + request.getFaultLevel()
                        + ", dispatchResult=" + dispatchResult.getMessage());

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
