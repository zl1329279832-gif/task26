package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.entity.DispatchRecord;
import com.maintenance.entity.Fault;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.Technician;
import com.maintenance.entity.TechnicianSkill;
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
import com.maintenance.mapper.TechnicianSkillMapper;
import com.maintenance.mapper.WorkOrderMapper;
import lombok.extern.slf4j.Slf4j;
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
    private final TechnicianSkillMapper technicianSkillMapper;
    private final SparePartService sparePartService;
    private final DowntimeService downtimeService;
    private final AutoDispatchService autoDispatchService;
    private final LocalMessageQueue messageQueue;
    private final AuditService auditService;

    public WorkOrderService(WorkOrderMapper workOrderMapper,
                            DispatchRecordMapper dispatchRecordMapper,
                            FaultMapper faultMapper,
                            EquipmentMapper equipmentMapper,
                            TechnicianService technicianService,
                            TechnicianSkillMapper technicianSkillMapper,
                            SparePartService sparePartService,
                            DowntimeService downtimeService,
                            AutoDispatchService autoDispatchService,
                            LocalMessageQueue messageQueue,
                            AuditService auditService) {
        this.workOrderMapper = workOrderMapper;
        this.dispatchRecordMapper = dispatchRecordMapper;
        this.faultMapper = faultMapper;
        this.equipmentMapper = equipmentMapper;
        this.technicianService = technicianService;
        this.technicianSkillMapper = technicianSkillMapper;
        this.sparePartService = sparePartService;
        this.downtimeService = downtimeService;
        this.autoDispatchService = autoDispatchService;
        this.messageQueue = messageQueue;
        this.auditService = auditService;
    }

    /**
     * Accept a work order: CREATED -> ACCEPTED.
     */
    @Transactional
    public WorkOrder accept(Long workOrderId, Long technicianId) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.ACCEPTED;
        validateTransition(currentStatus, targetStatus);

        order.setStatus(targetStatus.name());
        order.setAcceptedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        DispatchRecord dispatchRecord = dispatchRecordMapper.selectLatestByWorkOrder(workOrderId);
        if (dispatchRecord != null) {
            dispatchRecordMapper.updateAccepted(dispatchRecord.getId(), 1, LocalDateTime.now());
        }

        faultMapper.updateStatus(order.getFaultId(), FaultStatus.PROCESSING.name());
        technicianService.updateAvailability(technicianId, TechnicianAvailability.BUSY.name());

        publishStatusChange(order, currentStatus.name(), targetStatus.name());
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
        validateTransition(currentStatus, targetStatus);

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
        validateTransition(currentStatus, targetStatus);

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
     */
    @Transactional
    public WorkOrder suspend(Long workOrderId, String reason) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.SUSPENDED;
        validateTransition(currentStatus, targetStatus);

        order.setStatus(targetStatus.name());
        order.setSuspendedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        publishStatusChange(order, currentStatus.name(), targetStatus.name());
        auditService.log("WORK_ORDER", "SUSPEND", "WorkOrder", workOrderId, "SYSTEM",
                "Work order suspended, reason=" + reason);

        log.info("WorkOrder [{}] suspended, reason={}", workOrderId, reason);
        return order;
    }

    /**
     * Resume work order: SUSPENDED -> REPAIRING.
     */
    @Transactional
    public WorkOrder resume(Long workOrderId) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.REPAIRING;
        validateTransition(currentStatus, targetStatus);

        order.setStatus(targetStatus.name());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        publishStatusChange(order, currentStatus.name(), targetStatus.name());
        auditService.log("WORK_ORDER", "RESUME", "WorkOrder", workOrderId, "SYSTEM",
                "Work order resumed");

        log.info("WorkOrder [{}] resumed to REPAIRING", workOrderId);
        return order;
    }

    /**
     * Complete work order: REPAIRING -> COMPLETED.
     * Full completion workflow including parts consumption, workload update, downtime ending, etc.
     */
    @Transactional
    public WorkOrder complete(Long workOrderId, String repairNotes, BigDecimal laborCost) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.COMPLETED;
        validateTransition(currentStatus, targetStatus);

        // Update work order status and completion details
        order.setStatus(targetStatus.name());
        order.setCompletedAt(LocalDateTime.now());
        order.setRepairNotes(repairNotes);
        order.setLaborCost(laborCost != null ? laborCost : BigDecimal.ZERO);
        order.setUpdatedAt(LocalDateTime.now());

        // Consume all occupied spare parts
        sparePartService.consumeAllByWorkOrder(workOrderId);

        // Calculate total parts cost from occupation records
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

        // Release technician workload
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

        // Publish REPAIR_COMPLETED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("equipmentId", order.getEquipmentId());
        eventPayload.put("technicianId", order.getTechnicianId());
        eventPayload.put("partsCost", totalPartsCost);
        eventPayload.put("laborCost", laborCost);
        messageQueue.publish(EventType.REPAIR_COMPLETED.name(), eventPayload);

        publishStatusChange(order, currentStatus.name(), targetStatus.name());
        auditService.log("WORK_ORDER", "COMPLETE", "WorkOrder", workOrderId, "SYSTEM",
                "Work order completed: partsCost=" + totalPartsCost
                        + ", laborCost=" + laborCost
                        + ", repairNotes=" + repairNotes);

        log.info("WorkOrder [{}] completed, partsCost={}, laborCost={}",
                workOrderId, totalPartsCost, laborCost);
        return order;
    }

    /**
     * Reassign work order to a new technician.
     * Validates new technician availability and skill match.
     * On failure, publishes REASSIGN_FAILED and throws exception (transaction rolls back).
     * On success, releases spare parts from old assignment and creates new assignment.
     */
    @Transactional
    public WorkOrder reassign(Long workOrderId, Long newTechnicianId, String reason) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());

        // 1. Validate state machine allows reassignment
        if (!currentStatus.canTransitionTo(WorkOrderStatus.REASSIGNED)) {
            throw new BusinessException("Cannot reassign from status " + currentStatus.name());
        }

        // 2. Validate new technician exists
        Technician newTech = technicianService.getById(newTechnicianId);
        if (newTech == null) {
            publishReassignFailed(workOrderId, order.getTechnicianId(), newTechnicianId,
                    "New technician not found");
            throw new BusinessException("New technician not found, technicianId=" + newTechnicianId);
        }

        // 3. Validate new technician is online
        String newTechAvailability = newTech.getAvailability();
        if (TechnicianAvailability.OFFLINE.name().equals(newTechAvailability)
                || TechnicianAvailability.ON_LEAVE.name().equals(newTechAvailability)) {
            publishReassignFailed(workOrderId, order.getTechnicianId(), newTechnicianId,
                    "New technician is " + newTechAvailability);
            throw new BusinessException("Cannot reassign to technician " + newTechnicianId
                    + ": status is " + newTechAvailability);
        }

        // 4. Validate new technician has matching skill
        Fault fault = faultMapper.selectById(order.getFaultId());
        if (fault != null) {
            TechnicianSkill skill = technicianSkillMapper.selectByTechnicianAndType(
                    newTechnicianId, fault.getEquipmentType());
            if (skill == null) {
                publishReassignFailed(workOrderId, order.getTechnicianId(), newTechnicianId,
                        "No matching skill for equipmentType=" + fault.getEquipmentType());
                throw new BusinessException("Technician " + newTechnicianId
                        + " lacks skill for equipmentType=" + fault.getEquipmentType());
            }
            if (skill.getCertifiedFaultLevel() == null
                    || skill.getCertifiedFaultLevel() < fault.getFaultLevel()) {
                publishReassignFailed(workOrderId, order.getTechnicianId(), newTechnicianId,
                        "Certified level " + skill.getCertifiedFaultLevel()
                                + " < required " + fault.getFaultLevel());
                throw new BusinessException("Technician " + newTechnicianId
                        + " certification level insufficient for fault level " + fault.getFaultLevel());
            }
        }

        Long oldTechnicianId = order.getTechnicianId();

        // 5. Release spare parts occupied by the old assignment
        sparePartService.releaseOccupationsByWorkOrder(workOrderId);

        // 6. Release old technician workload
        if (oldTechnicianId != null) {
            technicianService.decrementWorkload(oldTechnicianId);
            List<WorkOrder> oldActiveOrders = workOrderMapper.selectActiveByTechnicianId(oldTechnicianId);
            if (oldActiveOrders == null || oldActiveOrders.isEmpty()) {
                technicianService.updateAvailability(oldTechnicianId,
                        TechnicianAvailability.AVAILABLE.name());
            }
        }

        // 7. Set work order to REASSIGNED transitionally
        order.setStatus(WorkOrderStatus.REASSIGNED.name());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        publishStatusChange(order, currentStatus.name(), WorkOrderStatus.REASSIGNED.name());

        // 8. Create new dispatch record (type=REASSIGN)
        DispatchRecord newDispatch = new DispatchRecord();
        newDispatch.setWorkOrderId(workOrderId);
        newDispatch.setTechnicianId(newTechnicianId);
        newDispatch.setDispatchType(DispatchType.REASSIGN.name());
        newDispatch.setDispatchScore(BigDecimal.ZERO);
        newDispatch.setIsAccepted(0);
        newDispatch.setCreatedAt(LocalDateTime.now());
        dispatchRecordMapper.insert(newDispatch);

        // 9. Update work order: new technician, status=CREATED, reassign_count+1
        order.setTechnicianId(newTechnicianId);
        order.setStatus(WorkOrderStatus.CREATED.name());
        order.setReassignCount((order.getReassignCount() != null ? order.getReassignCount() : 0) + 1);
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        // 10. New technician: workload+1
        technicianService.incrementWorkload(newTechnicianId);

        // 11. Publish WORK_ORDER_REASSIGNED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("oldTechnicianId", oldTechnicianId);
        eventPayload.put("newTechnicianId", newTechnicianId);
        eventPayload.put("reason", reason);
        messageQueue.publish(EventType.WORK_ORDER_REASSIGNED.name(), eventPayload);

        publishStatusChange(order, WorkOrderStatus.REASSIGNED.name(), WorkOrderStatus.CREATED.name());

        auditService.log("WORK_ORDER", "REASSIGN", "WorkOrder", workOrderId, "SYSTEM",
                "Work order reassigned from technician#" + oldTechnicianId
                        + " to technician#" + newTechnicianId
                        + ", reason=" + reason
                        + ", spare parts released");

        log.info("WorkOrder [{}] reassigned from [{}] to [{}], reason={}, parts released",
                workOrderId, oldTechnicianId, newTechnicianId, reason);
        return order;
    }

    /**
     * Rework a completed order: COMPLETED -> REWORK.
     * Creates a new linked work order and triggers dispatch.
     */
    @Transactional
    public WorkOrder rework(Long workOrderId, String reason) {
        WorkOrder originalOrder = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(originalOrder.getStatus());
        WorkOrderStatus targetStatus = WorkOrderStatus.REWORK;
        validateTransition(currentStatus, targetStatus);

        // 1. Mark original order as REWORK
        originalOrder.setStatus(targetStatus.name());
        originalOrder.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(originalOrder);

        publishStatusChange(originalOrder, currentStatus.name(), targetStatus.name());

        // 2. Re-open the fault
        faultMapper.updateStatus(originalOrder.getFaultId(), FaultStatus.PROCESSING.name());

        // 3. Update equipment status back to FAULT
        equipmentMapper.updateStatus(originalOrder.getEquipmentId(), EquipmentStatus.FAULT.name());

        // 4. Create new work order linked to original
        WorkOrder newOrder = new WorkOrder();
        newOrder.setOrderCode(generateOrderCode());
        newOrder.setFaultId(originalOrder.getFaultId());
        newOrder.setEquipmentId(originalOrder.getEquipmentId());
        newOrder.setStatus(WorkOrderStatus.CREATED.name());
        newOrder.setPriority(originalOrder.getPriority());
        newOrder.setFaultDescription(originalOrder.getFaultDescription());
        newOrder.setReassignCount(0);
        newOrder.setEscalateCount(0);
        newOrder.setIsRerepair(1);
        newOrder.setOriginalOrderId(workOrderId);
        newOrder.setCreatedAt(LocalDateTime.now());
        newOrder.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.insert(newOrder);

        // 5. Start new downtime record
        Fault fault = faultMapper.selectById(originalOrder.getFaultId());
        downtimeService.startDowntime(originalOrder.getEquipmentId(), newOrder.getId(),
                originalOrder.getFaultId());

        // 6. Auto dispatch the new order
        if (fault != null) {
            com.maintenance.dto.DispatchResult dispatchResult;
            if (fault.getFaultLevel() >= 3) {
                dispatchResult = autoDispatchService.emergencyDispatch(newOrder, fault);
            } else {
                dispatchResult = autoDispatchService.autoDispatch(newOrder, fault);
            }
            log.info("Rework dispatch for new workOrder [{}], result={}",
                    newOrder.getId(), dispatchResult.getMessage());
        }

        // 7. Publish WORK_ORDER_REWORK event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("originalWorkOrderId", workOrderId);
        eventPayload.put("newWorkOrderId", newOrder.getId());
        eventPayload.put("newOrderCode", newOrder.getOrderCode());
        eventPayload.put("equipmentId", originalOrder.getEquipmentId());
        eventPayload.put("reason", reason);
        messageQueue.publish(EventType.WORK_ORDER_REWORK.name(), eventPayload);

        auditService.log("WORK_ORDER", "REWORK", "WorkOrder", workOrderId, "SYSTEM",
                "Work order rework initiated: newOrderId=" + newOrder.getId()
                        + ", reason=" + reason);

        log.info("WorkOrder [{}] marked as REWORK, new order [{}] created", workOrderId, newOrder.getId());
        return newOrder;
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
        }
        order.setEscalateCount(escalateCount + 1);
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        log.info("WorkOrder [{}] escalated: priority {} -> {}, escalateCount={}",
                workOrderId, currentPriority, order.getPriority(), order.getEscalateCount());

        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("oldPriority", currentPriority);
        eventPayload.put("newPriority", order.getPriority());
        eventPayload.put("escalateCount", order.getEscalateCount());
        messageQueue.publish(EventType.WORK_ORDER_ESCALATED.name(), eventPayload);

        auditService.log("WORK_ORDER", "ESCALATE", "WorkOrder", workOrderId, "SYSTEM",
                "Work order escalated: priority " + currentPriority + " -> " + order.getPriority()
                        + ", escalateCount=" + order.getEscalateCount());

        return order;
    }

    /**
     * Abnormal close: active status -> CLOSED_ABNORMAL.
     * Releases all resources (parts, technician) and ends downtime.
     */
    @Transactional
    public WorkOrder closeAbnormal(Long workOrderId, String reason) {
        WorkOrder order = getAndValidate(workOrderId);
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(order.getStatus());

        // Validate: only active or completed orders can be abnormally closed
        if (!currentStatus.canTransitionTo(WorkOrderStatus.CLOSED_ABNORMAL)) {
            throw new BusinessException("Cannot close order from status " + currentStatus.name());
        }

        // Release spare parts occupation
        sparePartService.releaseOccupationsByWorkOrder(workOrderId);

        // Release technician
        if (order.getTechnicianId() != null) {
            technicianService.decrementWorkload(order.getTechnicianId());
            List<WorkOrder> activeOrders = workOrderMapper.selectActiveByTechnicianId(order.getTechnicianId());
            if (activeOrders == null || activeOrders.isEmpty()) {
                technicianService.updateAvailability(order.getTechnicianId(),
                        TechnicianAvailability.AVAILABLE.name());
            }
        }

        // Update fault status to CLOSED
        if (order.getFaultId() != null) {
            faultMapper.updateStatus(order.getFaultId(), FaultStatus.CLOSED.name());
        }

        // End downtime record
        downtimeService.endDowntime(order.getEquipmentId(), workOrderId);

        // Update work order status
        order.setStatus(WorkOrderStatus.CLOSED_ABNORMAL.name());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);

        // Publish WORK_ORDER_CLOSED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrderId);
        eventPayload.put("orderCode", order.getOrderCode());
        eventPayload.put("previousStatus", currentStatus.name());
        eventPayload.put("technicianId", order.getTechnicianId());
        eventPayload.put("reason", reason);
        messageQueue.publish(EventType.WORK_ORDER_CLOSED.name(), eventPayload);

        publishStatusChange(order, currentStatus.name(), WorkOrderStatus.CLOSED_ABNORMAL.name());

        auditService.log("WORK_ORDER", "CLOSE_ABNORMAL", "WorkOrder", workOrderId, "SYSTEM",
                "Work order abnormally closed from status=" + currentStatus.name()
                        + ", reason=" + reason
                        + ", spare parts released");

        log.info("WorkOrder [{}] abnormally closed from [{}], reason={}", workOrderId, currentStatus.name(), reason);
        return order;
    }

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
        messageQueue.publish(EventType.STATUS_CHANGED.name(), eventPayload);
    }

    private void publishReassignFailed(Long workOrderId, Long oldTechnicianId,
                                        Long newTechnicianId, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("workOrderId", workOrderId);
        payload.put("oldTechnicianId", oldTechnicianId);
        payload.put("newTechnicianId", newTechnicianId);
        payload.put("reason", reason);
        messageQueue.publish(EventType.REASSIGN_FAILED.name(), payload);

        auditService.log("WORK_ORDER", "REASSIGN_FAILED", "WorkOrder", workOrderId, "SYSTEM",
                "Reassign failed: from technician#" + oldTechnicianId
                        + " to technician#" + newTechnicianId
                        + ", reason=" + reason);
    }

    private void validateTransition(WorkOrderStatus current, WorkOrderStatus target) {
        if (!current.canTransitionTo(target)) {
            throw new BusinessException("Cannot transition from " + current.name()
                    + " to " + target.name());
        }
    }

    private WorkOrder getAndValidate(Long workOrderId) {
        WorkOrder order = workOrderMapper.selectById(workOrderId);
        if (order == null) {
            throw new BusinessException("Work order not found, workOrderId=" + workOrderId);
        }
        return order;
    }
}
