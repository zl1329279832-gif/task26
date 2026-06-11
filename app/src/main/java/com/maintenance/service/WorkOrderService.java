package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.entity.DispatchRecord;
import com.maintenance.entity.DowntimeRecord;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.Technician;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.DispatchType;
import com.maintenance.enums.EquipmentStatus;
import com.maintenance.enums.EventType;
import com.maintenance.enums.FaultStatus;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.enums.WorkOrderStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.infrastructure.queue.TransactionAwareEventPublisher;
import com.maintenance.mapper.DispatchRecordMapper;
import com.maintenance.mapper.EquipmentMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.WorkOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
@Service
public class WorkOrderService {

    private static final DateTimeFormatter CODE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Random RANDOM = new Random();

    private final WorkOrderMapper workOrderMapper;
    private final DispatchRecordMapper dispatchRecordMapper;
    private final FaultMapper faultMapper;
    private final EquipmentMapper equipmentMapper;
    private final TechnicianService technicianService;
    private final SparePartService sparePartService;
    private final DowntimeService downtimeService;
    private final LocalMessageQueue messageQueue;
    private final TransactionAwareEventPublisher txPublisher;
    private final AuditService auditService;
    private final PredictiveDispatchService predictiveDispatchService;
    private final SlaService slaService;

    public WorkOrderService(WorkOrderMapper workOrderMapper,
                            DispatchRecordMapper dispatchRecordMapper,
                            FaultMapper faultMapper,
                            EquipmentMapper equipmentMapper,
                            TechnicianService technicianService,
                            SparePartService sparePartService,
                            DowntimeService downtimeService,
                            LocalMessageQueue messageQueue,
                            TransactionAwareEventPublisher txPublisher,
                            AuditService auditService,
                            @Lazy PredictiveDispatchService predictiveDispatchService,
                            SlaService slaService) {
        this.workOrderMapper = workOrderMapper;
        this.dispatchRecordMapper = dispatchRecordMapper;
        this.faultMapper = faultMapper;
        this.equipmentMapper = equipmentMapper;
        this.technicianService = technicianService;
        this.sparePartService = sparePartService;
        this.downtimeService = downtimeService;
        this.messageQueue = messageQueue;
        this.txPublisher = txPublisher;
        this.auditService = auditService;
        this.predictiveDispatchService = predictiveDispatchService;
        this.slaService = slaService;
    }

    /**
     * Accept a work order: CREATED -> ACCEPTED.
     */
    @Transactional
    public WorkOrder accept(Long workOrderId, Long technicianId) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.ACCEPTED;

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException("Cannot transition from " + currentStatus.name()
                    + " to " + targetStatus.name());
        }

        // 1. Update work order status
        order.setStatus(targetStatus.name());
        order.setAcceptedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        // 2. Update dispatch record
        DispatchRecord dispatchRecord = dispatchRecordMapper.selectLatestByWorkOrder(workOrderId);
        if (dispatchRecord != null) {
            dispatchRecordMapper.updateAccepted(dispatchRecord.getId(), 1, LocalDateTime.now());
        }

        // 3. Update fault status to PROCESSING
        faultMapper.updateStatus(order.getFaultId(), FaultStatus.PROCESSING.name());

        // 4. Update technician availability to BUSY
        technicianService.updateAvailability(technicianId, TechnicianAvailability.BUSY.name());

        // 5. Publish STATUS_CHANGED event (deferred)
        publishStatusChange(order, currentStatus.name(), targetStatus.name());

        // 6. Audit log
        auditService.log("WORK_ORDER", "ACCEPT", "WorkOrder", workOrderId,
                "Technician#" + technicianId,
                "Work order accepted by technician " + technicianId);

        log.info("WorkOrder [{}] accepted by technician [{}]", workOrderId, technicianId);
        return order;
    }

    /**
     * Arrive at site: ACCEPTED -> ARRIVED.
     */
    @Transactional
    public WorkOrder arrive(Long workOrderId) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.ARRIVED;

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException("Cannot transition from " + currentStatus.name()
                    + " to " + targetStatus.name());
        }

        order.setStatus(targetStatus.name());
        order.setArrivedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        publishStatusChange(order, currentStatus.name(), targetStatus.name());

        auditService.log("WORK_ORDER", "ARRIVE", "WorkOrder", workOrderId, "SYSTEM",
                "Technician arrived at site");

        log.info("WorkOrder [{}] status changed to ARRIVED", workOrderId);
        return order;
    }

    /**
     * Start repair: ARRIVED -> REPAIRING.
     */
    @Transactional
    public WorkOrder startRepair(Long workOrderId) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.REPAIRING;

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException("Cannot transition from " + currentStatus.name()
                    + " to " + targetStatus.name());
        }

        order.setStatus(targetStatus.name());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        publishStatusChange(order, currentStatus.name(), targetStatus.name());

        auditService.log("WORK_ORDER", "START_REPAIR", "WorkOrder", workOrderId, "SYSTEM",
                "Repair started");

        log.info("WorkOrder [{}] status changed to REPAIRING", workOrderId);
        return order;
    }

    /**
     * Suspend work order: ACCEPTED/ARRIVED/REPAIRING -> SUSPENDED.
     * <p>
     * Fix: Also pauses the SLA record so remaining time is preserved,
     * and ends the downtime record so duration is not counted while suspended.
     */
    @Transactional
    public WorkOrder suspend(Long workOrderId, String reason) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.SUSPENDED;

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException("Cannot transition from " + currentStatus.name()
                    + " to " + targetStatus.name());
        }

        order.setStatus(targetStatus.name());
        order.setSuspendedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        // Pause downtime record
        downtimeService.endDowntime(order.getEquipmentId(), workOrderId);
        log.info("Downtime paused for workOrder [{}] due to suspension", workOrderId);

        // FIX: Also pause SLA so remaining time is preserved
        slaService.pauseSla(workOrderId, "工单暂停: " + reason);
        log.info("SLA paused for workOrder [{}] due to suspension", workOrderId);

        publishStatusChange(order, currentStatus.name(), targetStatus.name());

        auditService.log("WORK_ORDER", "SUSPEND", "WorkOrder", workOrderId, "SYSTEM",
                "Work order suspended, reason=" + reason);

        log.info("WorkOrder [{}] suspended, reason={}", workOrderId, reason);
        return order;
    }

    /**
     * Resume work order: SUSPENDED -> REPAIRING.
     * <p>
     * Fix: Restarts the downtime record AND resumes the SLA record.
     */
    @Transactional
    public WorkOrder resume(Long workOrderId) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.REPAIRING;

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException("Cannot transition from " + currentStatus.name()
                    + " to " + targetStatus.name());
        }

        order.setStatus(targetStatus.name());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        // Restart downtime record
        downtimeService.startDowntime(order.getEquipmentId(), workOrderId, order.getFaultId());
        log.info("Downtime restarted for workOrder [{}] on resume", workOrderId);

        // FIX: Resume SLA so deadline is recalculated from remaining time
        slaService.resumeSla(workOrderId);
        log.info("SLA resumed for workOrder [{}] on resume", workOrderId);

        publishStatusChange(order, currentStatus.name(), targetStatus.name());

        auditService.log("WORK_ORDER", "RESUME", "WorkOrder", workOrderId, "SYSTEM",
                "Work order resumed");

        log.info("WorkOrder [{}] resumed to REPAIRING", workOrderId);
        return order;
    }

    /**
     * Complete work order: REPAIRING -> COMPLETED.
     */
    @Transactional
    public WorkOrder complete(Long workOrderId, String repairNotes, BigDecimal laborCost) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.COMPLETED;

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException("Cannot transition from " + currentStatus.name()
                    + " to " + targetStatus.name());
        }

        order.setStatus(targetStatus.name());
        order.setCompletedAt(LocalDateTime.now());
        order.setRepairNotes(repairNotes);
        order.setLaborCost(laborCost != null ? laborCost : BigDecimal.ZERO);
        order.setUpdatedAt(LocalDateTime.now());

        // Consume all occupied spare parts
        sparePartService.consumeAllByWorkOrder(workOrderId);

        // Calculate total parts cost
        List<SparePartOccupation> occupations = sparePartService.getOccupationsByWorkOrder(workOrderId);
        BigDecimal totalPartsCost = BigDecimal.ZERO;
        for (SparePartOccupation occ : occupations) {
            if (OccupationStatus.CONSUMED.name().equals(occ.getStatus())) {
                SparePart part = sparePartService.getById(occ.getPartId());
                if (part != null && part.getUnitPrice() != null) {
                    totalPartsCost = totalPartsCost.add(
                            part.getUnitPrice().multiply(BigDecimal.valueOf(occ.getQuantity())));
                }
            }
        }
        order.setPartsCost(totalPartsCost);
        workOrderMapper.updateById(order);

        // Update technician workload(-1) and availability
        if (order.getTechnicianId() != null) {
            technicianService.decrementWorkload(order.getTechnicianId());
            List<WorkOrder> activeOrders = workOrderMapper.selectActiveByTechnicianId(order.getTechnicianId());
            if (activeOrders == null || activeOrders.isEmpty()) {
                technicianService.updateAvailability(order.getTechnicianId(),
                        TechnicianAvailability.AVAILABLE.name());
            }
        }

        // End downtime record
        downtimeService.endDowntime(order.getEquipmentId(), workOrderId);

        // Update fault status to RESOLVED
        faultMapper.updateStatus(order.getFaultId(), FaultStatus.RESOLVED.name());

        // Update equipment status to RUNNING
        equipmentMapper.updateStatus(order.getEquipmentId(), EquipmentStatus.RUNNING.name());

        // Publish REPAIR_COMPLETED event (deferred)
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("equipmentId", order.getEquipmentId());
        eventPayload.put("technicianId", order.getTechnicianId());
        eventPayload.put("partsCost", totalPartsCost);
        eventPayload.put("laborCost", laborCost);
        txPublisher.publish(EventType.REPAIR_COMPLETED.name(), eventPayload);

        // Audit log
        auditService.log("WORK_ORDER", "COMPLETE", "WorkOrder", workOrderId, "SYSTEM",
                "Work order completed: partsCost=" + totalPartsCost
                        + ", laborCost=" + laborCost
                        + ", repairNotes=" + repairNotes);

        // Finalize SLA
        slaService.finalizeSla(workOrderId);

        log.info("WorkOrder [{}] completed, partsCost={}, laborCost={}",
                workOrderId, totalPartsCost, laborCost);
        return order;
    }

    /**
     * Reassign work order to a new technician.
     * <p>
     * Fixes applied:
     * <ol>
     *   <li>End the downtime record for the old assignment.</li>
     *   <li>Validate new technician exists and is not OFFLINE/ON_LEAVE.</li>
     *   <li>FIX: Remove double-release of spare parts — only call
     *       {@code sparePartService.releaseOccupationsByWorkOrder} once.
     *       Previously both {@code releasePreOccupiedParts} and
     *       {@code releaseOccupationsByWorkOrder} were called, causing double-release.</li>
     *   <li>On failure, @Transactional ensures full rollback.</li>
     * </ol>
     */
    @Transactional
    public WorkOrder reassign(Long workOrderId, Long newTechnicianId, String reason) {
        WorkOrder order = getAndValidate(workOrderId);

        // Validate new technician
        Technician newTech = technicianService.getById(newTechnicianId);
        if (newTech == null) {
            throw new BusinessException("New technician not found, technicianId=" + newTechnicianId);
        }

        // Reject reassignment to OFFLINE or ON_LEAVE technician
        String newTechAvailability = newTech.getAvailability();
        if (TechnicianAvailability.OFFLINE.name().equals(newTechAvailability)
                || TechnicianAvailability.ON_LEAVE.name().equals(newTechAvailability)) {
            throw new BusinessException(
                    "Cannot reassign to technician#" + newTechnicianId
                            + ": availability=" + newTechAvailability);
        }

        Long oldTechnicianId = order.getTechnicianId();

        // 1. End the current downtime record for the old assignment
        if (order.getEquipmentId() != null) {
            DowntimeRecord ended = downtimeService.endDowntime(order.getEquipmentId(), workOrderId);
            if (ended != null) {
                log.info("Downtime record ended for reassignment, workOrder={}, equipment={}",
                        workOrderId, order.getEquipmentId());
            }
        }

        // 2. Set work order status to REASSIGNED
        order.setStatus(WorkOrderStatus.REASSIGNED.name());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        // 3. FIX: Release spare parts ONCE (previously called both
        //    releasePreOccupiedParts and releaseOccupationsByWorkOrder, causing double-release)
        sparePartService.releaseOccupationsByWorkOrder(workOrderId);

        // 4. Old technician: workload-1, check availability
        if (oldTechnicianId != null) {
            technicianService.decrementWorkload(oldTechnicianId);
            List<WorkOrder> oldActiveOrders = workOrderMapper.selectActiveByTechnicianId(oldTechnicianId);
            if (oldActiveOrders == null || oldActiveOrders.isEmpty()) {
                technicianService.updateAvailability(oldTechnicianId,
                        TechnicianAvailability.AVAILABLE.name());
            }
        }

        // 5. Create new dispatch record (type=REASSIGN)
        DispatchRecord newDispatch = new DispatchRecord();
        newDispatch.setWorkOrderId(workOrderId);
        newDispatch.setTechnicianId(newTechnicianId);
        newDispatch.setDispatchType(DispatchType.REASSIGN.name());
        newDispatch.setDispatchScore(BigDecimal.ZERO);
        newDispatch.setIsAccepted(0);
        newDispatch.setCreatedAt(LocalDateTime.now());
        dispatchRecordMapper.insert(newDispatch);

        // 6. Update work order: new technician, status=CREATED, reassign_count+1
        order.setTechnicianId(newTechnicianId);
        order.setStatus(WorkOrderStatus.CREATED.name());
        order.setReassignCount((order.getReassignCount() != null ? order.getReassignCount() : 0) + 1);
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        // 7. New technician: workload+1
        technicianService.incrementWorkload(newTechnicianId);

        // 8. Publish WORK_ORDER_REASSIGNED event (deferred)
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("oldTechnicianId", oldTechnicianId);
        eventPayload.put("newTechnicianId", newTechnicianId);
        eventPayload.put("reason", reason);
        txPublisher.publish(EventType.WORK_ORDER_REASSIGNED.name(), eventPayload);

        // 9. Audit log with reason
        auditService.log("WORK_ORDER", "REASSIGN", "WorkOrder", workOrderId, "SYSTEM",
                "Work order reassigned from technician#" + oldTechnicianId
                        + " to technician#" + newTechnicianId
                        + ", reason=" + reason);

        log.info("WorkOrder [{}] reassigned from [{}] to [{}], reason={}",
                workOrderId, oldTechnicianId, newTechnicianId, reason);
        return order;
    }

    /**
     * Escalate work order: increase priority (max 4), increment escalate_count.
     */
    @Transactional
    public WorkOrder escalate(Long workOrderId) {
        WorkOrder order = getAndValidate(workOrderId);

        int currentPriority = order.getPriority() != null ? order.getPriority() : 1;
        int escalateCount = order.getEscalateCount() != null ? order.getEscalateCount() : 0;

        if (currentPriority < 4) {
            order.setPriority(currentPriority + 1);
            order.setEscalateCount(escalateCount + 1);
            order.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.updateById(order);
            log.info("WorkOrder [{}] escalated from priority {} to {}",
                    workOrderId, currentPriority, currentPriority + 1);
        } else {
            order.setEscalateCount(escalateCount + 1);
            order.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.updateById(order);
            log.info("WorkOrder [{}] already at max priority, escalate_count incremented to {}",
                    workOrderId, escalateCount + 1);
        }

        // Publish WORK_ORDER_ESCALATED event (deferred)
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("oldPriority", currentPriority);
        eventPayload.put("newPriority", order.getPriority());
        eventPayload.put("escalateCount", order.getEscalateCount());
        txPublisher.publish(EventType.WORK_ORDER_ESCALATED.name(), eventPayload);

        auditService.log("WORK_ORDER", "ESCALATE", "WorkOrder", workOrderId, "SYSTEM",
                "Work order escalated: priority " + currentPriority + " -> " + order.getPriority()
                        + ", escalateCount=" + order.getEscalateCount());

        return order;
    }

    /**
     * Abnormal close: any status -> CLOSED_ABNORMAL.
     * Releases all resources (parts, technician) and ends downtime.
     * <p>
     * Fix: Only release spare parts once (not double-release).
     */
    @Transactional
    public WorkOrder closeAbnormal(Long workOrderId, String reason) {
        WorkOrder order = getAndValidate(workOrderId);
        String oldStatus = order.getStatus();

        // 1. Release spare parts occupation ONCE
        sparePartService.releaseOccupationsByWorkOrder(workOrderId);

        // 2. Release technician (workload-1)
        if (order.getTechnicianId() != null) {
            technicianService.decrementWorkload(order.getTechnicianId());
            List<WorkOrder> activeOrders = workOrderMapper.selectActiveByTechnicianId(order.getTechnicianId());
            if (activeOrders == null || activeOrders.isEmpty()) {
                technicianService.updateAvailability(order.getTechnicianId(),
                        TechnicianAvailability.AVAILABLE.name());
            }
        }

        // 3. Update fault status to CLOSED
        if (order.getFaultId() != null) {
            faultMapper.updateStatus(order.getFaultId(), FaultStatus.CLOSED.name());
        }

        // 4. End downtime record
        if (order.getEquipmentId() != null) {
            downtimeService.endDowntime(order.getEquipmentId(), workOrderId);
        }

        // 5. Update work order status
        order.setStatus(WorkOrderStatus.CLOSED_ABNORMAL.name());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        // 5b. Finalize SLA
        slaService.finalizeSla(workOrderId);

        // 6. Publish WORK_ORDER_CLOSED event (deferred)
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("previousStatus", oldStatus);
        eventPayload.put("reason", reason);
        txPublisher.publish(EventType.WORK_ORDER_CLOSED.name(), eventPayload);

        // 7. Audit log
        auditService.log("WORK_ORDER", "CLOSE_ABNORMAL", "WorkOrder", workOrderId, "SYSTEM",
                "Work order abnormally closed from status=" + oldStatus + ", reason=" + reason);

        log.info("WorkOrder [{}] abnormally closed from [{}], reason={}",
                workOrderId, oldStatus, reason);
        return order;
    }

    // ========== Query Methods ==========

    public WorkOrder getById(Long id) {
        return workOrderMapper.selectById(id);
    }

    public List<WorkOrder> getByTechnician(Long technicianId) {
        return workOrderMapper.selectByTechnicianId(technicianId);
    }

    public List<WorkOrder> getByStatus(String status) {
        return workOrderMapper.selectByStatus(status);
    }

    public List<WorkOrder> getActiveByTechnician(Long technicianId) {
        return workOrderMapper.selectActiveByTechnicianId(technicianId);
    }

    // ========== Internal Helpers ==========

    private String generateOrderCode() {
        String timestamp = LocalDateTime.now().format(CODE_FORMATTER);
        int randomNum = RANDOM.nextInt(10000);
        return "WO" + timestamp + String.format("%04d", randomNum);
    }

    private void publishStatusChange(WorkOrder order, String oldStatus, String newStatus) {
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", order.getId());
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("oldStatus", oldStatus);
        eventPayload.put("newStatus", newStatus);
        eventPayload.put("technicianId", order.getTechnicianId());
        eventPayload.put("equipmentId", order.getEquipmentId());
        txPublisher.publish(EventType.STATUS_CHANGED.name(), eventPayload);
    }

    private WorkOrder getAndValidate(Long workOrderId) {
        WorkOrder order = workOrderMapper.selectById(workOrderId);
        if (order == null) {
            throw new BusinessException("Work order not found, workOrderId=" + workOrderId);
        }
        return order;
    }
}
