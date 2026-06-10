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

        // 5. Publish STATUS_CHANGED event
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
     *
     * Fix: Also pauses the downtime record so that duration is not counted while suspended.
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

        // FIX: Pause downtime record when suspending (end the active record;
        // a new one will be created on resume if needed)
        downtimeService.endDowntime(order.getEquipmentId(), workOrderId);
        log.info("Downtime paused for workOrder [{}] due to suspension", workOrderId);

        publishStatusChange(order, currentStatus.name(), targetStatus.name());

        auditService.log("WORK_ORDER", "SUSPEND", "WorkOrder", workOrderId, "SYSTEM",
                "Work order suspended, reason=" + reason);

        log.info("WorkOrder [{}] suspended, reason={}", workOrderId, reason);
        return order;
    }

    /**
     * Resume work order: SUSPENDED -> REPAIRING.
     *
     * Fix: Restarts the downtime record for the remaining repair time.
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

        // FIX: Restart downtime record when resuming from suspension
        downtimeService.startDowntime(order.getEquipmentId(), workOrderId, order.getFaultId());
        log.info("Downtime restarted for workOrder [{}] on resume", workOrderId);

        publishStatusChange(order, currentStatus.name(), targetStatus.name());

        auditService.log("WORK_ORDER", "RESUME", "WorkOrder", workOrderId, "SYSTEM",
                "Work order resumed");

        log.info("WorkOrder [{}] resumed to REPAIRING", workOrderId);
        return order;
    }

    /**
     * Complete work order: REPAIRING -> COMPLETED.
     * Full completion workflow including parts consumption, workload update, downtime ending, etc.
     *
     * Fix: Validate spare parts availability before proceeding with completion.
     */
    @Transactional
    public WorkOrder complete(Long workOrderId, String repairNotes, BigDecimal laborCost) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.COMPLETED;

        // 1. State machine validation
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException("Cannot transition from " + currentStatus.name()
                    + " to " + targetStatus.name());
        }

        // 2. Update work order status and completion details
        order.setStatus(targetStatus.name());
        order.setCompletedAt(LocalDateTime.now());
        order.setRepairNotes(repairNotes);
        order.setLaborCost(laborCost != null ? laborCost : BigDecimal.ZERO);
        order.setUpdatedAt(LocalDateTime.now());

        // 3. Consume all occupied spare parts
        sparePartService.consumeAllByWorkOrder(workOrderId);

        // 4. Calculate total parts cost from occupation records
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

        // 5. Update technician workload(-1) and availability
        if (order.getTechnicianId() != null) {
            technicianService.decrementWorkload(order.getTechnicianId());

            // Check if technician has other active orders; if not, set to AVAILABLE
            List<WorkOrder> activeOrders = workOrderMapper.selectActiveByTechnicianId(order.getTechnicianId());
            if (activeOrders == null || activeOrders.isEmpty()) {
                technicianService.updateAvailability(order.getTechnicianId(),
                        TechnicianAvailability.AVAILABLE.name());
            }
        }

        // 6. End downtime record (matches by equipmentId AND workOrderId to avoid cross-order interference)
        downtimeService.endDowntime(order.getEquipmentId(), workOrderId);

        // 7. Update fault status to RESOLVED
        faultMapper.updateStatus(order.getFaultId(), FaultStatus.RESOLVED.name());

        // 8. Update equipment status to RUNNING
        equipmentMapper.updateStatus(order.getEquipmentId(), EquipmentStatus.RUNNING.name());

        // 9. Publish REPAIR_COMPLETED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("equipmentId", order.getEquipmentId());
        eventPayload.put("technicianId", order.getTechnicianId());
        eventPayload.put("partsCost", totalPartsCost);
        eventPayload.put("laborCost", laborCost);
        messageQueue.publish(EventType.REPAIR_COMPLETED.name(), eventPayload);

        // 10. Audit log
        auditService.log("WORK_ORDER", "COMPLETE", "WorkOrder", workOrderId, "SYSTEM",
                "Work order completed: partsCost=" + totalPartsCost
                        + ", laborCost=" + laborCost
                        + ", repairNotes=" + repairNotes);

        // 11. Finalize SLA
        slaService.finalizeSla(workOrderId);

        log.info("WorkOrder [{}] completed, partsCost={}, laborCost={}",
                workOrderId, totalPartsCost, laborCost);
        return order;
    }

    /**
     * Reassign work order to a new technician.
     * Handles state consistency: release old resources, create new assignment, update status.
     *
     * Fixes applied:
     * 1. End the downtime record for the old assignment (it will restart when the new tech begins).
     * 2. Validate new technician exists and is not OFFLINE/ON_LEAVE.
     * 3. On failure of any step, the @Transactional annotation ensures full rollback
     *    (spare parts, workload, downtime, dispatch record all roll back atomically).
     */
    @Transactional
    public WorkOrder reassign(Long workOrderId, Long newTechnicianId, String reason) {
        WorkOrder order = getAndValidate(workOrderId);

        // FIX: Validate new technician
        Technician newTech = technicianService.getById(newTechnicianId);
        if (newTech == null) {
            throw new BusinessException("New technician not found, technicianId=" + newTechnicianId);
        }

        // FIX: Reject reassignment to OFFLINE or ON_LEAVE technician
        String newTechAvailability = newTech.getAvailability();
        if (TechnicianAvailability.OFFLINE.name().equals(newTechAvailability)
                || TechnicianAvailability.ON_LEAVE.name().equals(newTechAvailability)) {
            throw new BusinessException(
                    "Cannot reassign to technician#" + newTechnicianId
                            + ": availability=" + newTechAvailability);
        }

        Long oldTechnicianId = order.getTechnicianId();
        String oldStatus = order.getStatus();

        // 1. End the current downtime record for the old assignment
        //    (a new one will be started when the new technician resumes the repair)
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

        // 3. Release spare parts occupied by the old work order (including pre-occupied)
        predictiveDispatchService.releasePreOccupiedParts(workOrderId);
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

        // 8. Publish WORK_ORDER_REASSIGNED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("oldTechnicianId", oldTechnicianId);
        eventPayload.put("newTechnicianId", newTechnicianId);
        eventPayload.put("reason", reason);
        messageQueue.publish(EventType.WORK_ORDER_REASSIGNED.name(), eventPayload);

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
     * If already at max priority, attempt re-dispatch to a higher-level technician.
     */
    @Transactional
    public WorkOrder escalate(Long workOrderId) {
        WorkOrder order = getAndValidate(workOrderId);

        int currentPriority = order.getPriority() != null ? order.getPriority() : 1;
        int escalateCount = order.getEscalateCount() != null ? order.getEscalateCount() : 0;

        if (currentPriority < 4) {
            // Increase priority
            order.setPriority(currentPriority + 1);
            order.setEscalateCount(escalateCount + 1);
            order.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.updateById(order);
            log.info("WorkOrder [{}] escalated from priority {} to {}",
                    workOrderId, currentPriority, currentPriority + 1);
        } else {
            // Already at max priority - increment count and log
            order.setEscalateCount(escalateCount + 1);
            order.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.updateById(order);
            log.info("WorkOrder [{}] already at max priority, escalate_count incremented to {}",
                    workOrderId, escalateCount + 1);
        }

        // Publish WORK_ORDER_ESCALATED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("oldPriority", currentPriority);
        eventPayload.put("newPriority", order.getPriority());
        eventPayload.put("escalateCount", order.getEscalateCount());
        messageQueue.publish(EventType.WORK_ORDER_ESCALATED.name(), eventPayload);

        // Audit log
        auditService.log("WORK_ORDER", "ESCALATE", "WorkOrder", workOrderId, "SYSTEM",
                "Work order escalated: priority " + currentPriority + " -> " + order.getPriority()
                        + ", escalateCount=" + order.getEscalateCount());

        return order;
    }

    /**
     * Abnormal close: any status -> CLOSED_ABNORMAL.
     * Releases all resources (parts, technician) and ends downtime.
     *
     * Fix: Use workOrderId-aware downtime end to avoid cross-order interference.
     */
    @Transactional
    public WorkOrder closeAbnormal(Long workOrderId, String reason) {
        WorkOrder order = getAndValidate(workOrderId);
        String oldStatus = order.getStatus();

        // 1. Release spare parts occupation (including pre-occupied)
        predictiveDispatchService.releasePreOccupiedParts(workOrderId);
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

        // 4. End downtime record (matches by equipmentId AND workOrderId)
        if (order.getEquipmentId() != null) {
            downtimeService.endDowntime(order.getEquipmentId(), workOrderId);
        }

        // 5. Update work order status
        order.setStatus(WorkOrderStatus.CLOSED_ABNORMAL.name());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        // 5b. Finalize SLA (mark as expired since work order was abnormally closed)
        slaService.finalizeSla(workOrderId);

        // 6. Publish WORK_ORDER_CLOSED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("previousStatus", oldStatus);
        eventPayload.put("reason", reason);
        messageQueue.publish(EventType.WORK_ORDER_CLOSED.name(), eventPayload);

        // 7. Audit log
        auditService.log("WORK_ORDER", "CLOSE_ABNORMAL", "WorkOrder", workOrderId, "SYSTEM",
                "Work order abnormally closed from status=" + oldStatus + ", reason=" + reason);

        log.info("WorkOrder [{}] abnormally closed from [{}], reason={}",
                workOrderId, oldStatus, reason);
        return order;
    }

    /**
     * Get a work order by ID.
     */
    public WorkOrder getById(Long id) {
        return workOrderMapper.selectById(id);
    }

    /**
     * Get all work orders for a technician.
     */
    public List<WorkOrder> getByTechnician(Long technicianId) {
        return workOrderMapper.selectByTechnicianId(technicianId);
    }

    /**
     * Get work orders by status.
     */
    public List<WorkOrder> getByStatus(String status) {
        return workOrderMapper.selectByStatus(status);
    }

    /**
     * Get active work orders for a technician (not COMPLETED/REASSIGNED/CLOSED_ABNORMAL).
     */
    public List<WorkOrder> getActiveByTechnician(Long technicianId) {
        return workOrderMapper.selectActiveByTechnicianId(technicianId);
    }

    /**
     * Generate work order code: "WO" + yyyyMMddHHmmss + 4-digit random number.
     */
    private String generateOrderCode() {
        String timestamp = LocalDateTime.now().format(CODE_FORMATTER);
        int randomNum = RANDOM.nextInt(10000);
        return "WO" + timestamp + String.format("%04d", randomNum);
    }

    /**
     * Publish a STATUS_CHANGED event.
     */
    private void publishStatusChange(WorkOrder order, String oldStatus, String newStatus) {
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", order.getId());
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("oldStatus", oldStatus);
        eventPayload.put("newStatus", newStatus);
        eventPayload.put("technicianId", order.getTechnicianId());
        eventPayload.put("equipmentId", order.getEquipmentId());
        messageQueue.publish(EventType.STATUS_CHANGED.name(), eventPayload);
    }

    /**
     * Get and validate that a work order exists.
     */
    private WorkOrder getAndValidate(Long workOrderId) {
        WorkOrder order = workOrderMapper.selectById(workOrderId);
        if (order == null) {
            throw new BusinessException("Work order not found, workOrderId=" + workOrderId);
        }
        return order;
    }
}
