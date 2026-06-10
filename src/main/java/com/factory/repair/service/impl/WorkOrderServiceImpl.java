package com.factory.repair.service.impl;

import com.factory.repair.exception.BusinessException;
import com.factory.repair.mapper.*;
import com.factory.repair.model.dto.SparePartApplyRequest;
import com.factory.repair.model.dto.WorkOrderActionRequest;
import com.factory.repair.model.dto.WorkOrderDTO;
import com.factory.repair.model.entity.*;
import com.factory.repair.model.enums.EquipmentStatus;
import com.factory.repair.model.enums.WorkOrderStatus;
import com.factory.repair.service.AuditLogService;
import com.factory.repair.service.WorkOrderService;
import com.factory.repair.statemachine.StateTransitionValidator;
import com.factory.repair.statemachine.WorkOrderEvent;
import com.factory.repair.statemachine.WorkOrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderServiceImpl implements WorkOrderService {

    private final WorkOrderMapper workOrderMapper;
    private final EquipmentMapper equipmentMapper;
    private final FaultTypeMapper faultTypeMapper;
    private final RepairWorkerMapper repairWorkerMapper;
    private final DispatchRecordMapper dispatchRecordMapper;
    private final WorkOrderStateMachine stateMachine;
    private final StateTransitionValidator validator;
    private final AuditLogService auditLogService;

    @Override
    public WorkOrder getById(Long id) {
        WorkOrder workOrder = workOrderMapper.selectById(id);
        if (workOrder == null) {
            throw new BusinessException("工单不存在: " + id);
        }
        return workOrder;
    }

    @Override
    public WorkOrderDTO getDetail(Long id) {
        WorkOrder wo = getById(id);
        return toDTO(wo);
    }

    @Override
    public List<WorkOrderDTO> listWorkOrders(String status, Integer faultLevel, Long equipmentId) {
        List<WorkOrder> orders = workOrderMapper.selectAll(status, faultLevel, equipmentId);
        List<WorkOrderDTO> result = new ArrayList<>();
        for (WorkOrder wo : orders) {
            result.add(toDTO(wo));
        }
        return result;
    }

    @Override
    @Transactional
    public void accept(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder workOrder = getById(workOrderId);
        String oldStatus = workOrder.getStatus();
        WorkOrderStatus newStatus = fireTransition(workOrder, WorkOrderEvent.ACCEPT);

        workOrder.setStatus(newStatus.name());
        workOrder.setAcceptedAt(LocalDateTime.now());
        workOrderMapper.update(workOrder);

        // 更新派工记录
        List<DispatchRecord> records = dispatchRecordMapper.selectByWorkOrderId(workOrderId);
        for (DispatchRecord record : records) {
            if ("PENDING".equals(record.getDispatchStatus())) {
                dispatchRecordMapper.updateStatus(record.getId(), "ACCEPTED", null);
                break;
            }
        }

        auditLogService.log("WorkOrder", workOrderId, "ACCEPT",
                request.getOperatorId(), request.getOperatorName(), oldStatus, newStatus.name(), null);
    }

    @Override
    @Transactional
    public void arrive(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder workOrder = getById(workOrderId);
        String oldStatus = workOrder.getStatus();
        WorkOrderStatus newStatus = fireTransition(workOrder, WorkOrderEvent.ARRIVE);

        workOrder.setStatus(newStatus.name());
        workOrder.setArrivedAt(LocalDateTime.now());
        workOrderMapper.update(workOrder);

        auditLogService.log("WorkOrder", workOrderId, "ARRIVE",
                request.getOperatorId(), request.getOperatorName(), oldStatus, newStatus.name(), null);
    }

    @Override
    @Transactional
    public void startRepair(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder workOrder = getById(workOrderId);
        String oldStatus = workOrder.getStatus();
        WorkOrderStatus newStatus = fireTransition(workOrder, WorkOrderEvent.START_REPAIR);

        workOrder.setStatus(newStatus.name());
        workOrderMapper.update(workOrder);

        // 更新设备状态为维修中
        equipmentMapper.updateStatus(workOrder.getEquipmentId(), EquipmentStatus.REPAIRING.getCode());

        auditLogService.log("WorkOrder", workOrderId, "START_REPAIR",
                request.getOperatorId(), request.getOperatorName(), oldStatus, newStatus.name(), null);
    }

    @Override
    @Transactional
    public void pause(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder workOrder = getById(workOrderId);
        String oldStatus = workOrder.getStatus();
        WorkOrderStatus newStatus = fireTransition(workOrder, WorkOrderEvent.PAUSE);

        workOrder.setStatus(newStatus.name());
        workOrder.setRemark(request.getRemark());
        workOrderMapper.update(workOrder);

        auditLogService.log("WorkOrder", workOrderId, "PAUSE",
                request.getOperatorId(), request.getOperatorName(), oldStatus, newStatus.name(), request.getRemark());
    }

    @Override
    @Transactional
    public void resume(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder workOrder = getById(workOrderId);
        String oldStatus = workOrder.getStatus();
        WorkOrderStatus newStatus = fireTransition(workOrder, WorkOrderEvent.RESUME);

        workOrder.setStatus(newStatus.name());
        workOrderMapper.update(workOrder);

        auditLogService.log("WorkOrder", workOrderId, "RESUME",
                request.getOperatorId(), request.getOperatorName(), oldStatus, newStatus.name(), null);
    }

    @Override
    @Transactional
    public void complete(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder workOrder = getById(workOrderId);
        validator.validateComplete(workOrder);
        String oldStatus = workOrder.getStatus();
        WorkOrderStatus newStatus = fireTransition(workOrder, WorkOrderEvent.COMPLETE);

        workOrder.setStatus(newStatus.name());
        workOrder.setCompletedAt(LocalDateTime.now());
        workOrderMapper.update(workOrder);

        // 更新维修人员任务数
        if (workOrder.getAssignedWorkerId() != null) {
            repairWorkerMapper.decrementCurrentTasks(workOrder.getAssignedWorkerId());
        }

        // 恢复设备状态
        equipmentMapper.updateStatus(workOrder.getEquipmentId(), EquipmentStatus.NORMAL.getCode());

        auditLogService.log("WorkOrder", workOrderId, "COMPLETE",
                request.getOperatorId(), request.getOperatorName(), oldStatus, newStatus.name(), null);
    }

    @Override
    @Transactional
    public void transfer(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder workOrder = getById(workOrderId);
        validator.validateTransfer(workOrder, request.getTargetWorkerId());

        String oldStatus = workOrder.getStatus();
        WorkOrderStatus newStatus = fireTransition(workOrder, WorkOrderEvent.TRANSFER);

        Long oldWorkerId = workOrder.getAssignedWorkerId();

        // 旧人员任务数减1
        if (oldWorkerId != null) {
            repairWorkerMapper.decrementCurrentTasks(oldWorkerId);
        }

        // 取消旧的PENDING派工记录
        dispatchRecordMapper.cancelByWorkOrderId(workOrderId);

        // 新人员
        RepairWorker newWorker = repairWorkerMapper.selectById(request.getTargetWorkerId());
        if (newWorker == null) {
            throw new BusinessException("目标维修人员不存在");
        }

        workOrder.setStatus(newStatus.name());
        workOrder.setAssignedWorkerId(newWorker.getId());
        workOrder.setAssignedCrewId(newWorker.getCrewId());
        workOrder.setDispatchedAt(LocalDateTime.now());
        workOrder.setAcceptedAt(null);
        workOrder.setArrivedAt(null);
        workOrderMapper.update(workOrder);

        // 创建新的派工记录
        DispatchRecord record = new DispatchRecord();
        record.setWorkOrderId(workOrderId);
        record.setWorkerId(newWorker.getId());
        record.setCrewId(newWorker.getCrewId());
        record.setDispatchType("TRANSFER");
        record.setDispatchStatus("PENDING");
        record.setDispatchedAt(LocalDateTime.now());
        record.setTimeoutMinutes(30);
        record.setRemark("转派: " + request.getRemark());
        dispatchRecordMapper.insert(record);

        // 新人员任务数加1
        repairWorkerMapper.incrementCurrentTasks(newWorker.getId());

        auditLogService.log("WorkOrder", workOrderId, "TRANSFER",
                request.getOperatorId(), request.getOperatorName(), oldStatus,
                newStatus.name(), "从" + oldWorkerId + "转派到" + newWorker.getId());
    }

    @Override
    @Transactional
    public void escalate(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder workOrder = getById(workOrderId);
        validator.validateEscalate(workOrder);

        String oldStatus = workOrder.getStatus();
        WorkOrderStatus newStatus = fireTransition(workOrder, WorkOrderEvent.ESCALATE);

        int newLevel = Math.min(workOrder.getFaultLevel() + 1, 3);

        // 旧人员任务数减1
        if (workOrder.getAssignedWorkerId() != null) {
            repairWorkerMapper.decrementCurrentTasks(workOrder.getAssignedWorkerId());
        }

        dispatchRecordMapper.cancelByWorkOrderId(workOrderId);

        workOrder.setStatus(newStatus.name());
        workOrder.setFaultLevel(newLevel);
        workOrder.setPriority(calculatePriority(newLevel));
        workOrder.setAssignedWorkerId(null);
        workOrder.setAssignedCrewId(null);
        workOrder.setDispatchedAt(null);
        workOrder.setAcceptedAt(null);
        workOrder.setArrivedAt(null);
        workOrderMapper.update(workOrder);

        auditLogService.log("WorkOrder", workOrderId, "ESCALATE",
                request.getOperatorId(), request.getOperatorName(), oldStatus,
                newStatus.name(), "故障等级升级至" + newLevel);
    }

    @Override
    @Transactional
    public void close(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder workOrder = getById(workOrderId);
        String oldStatus = workOrder.getStatus();
        WorkOrderStatus newStatus = fireTransition(workOrder, WorkOrderEvent.CLOSE);

        workOrder.setStatus(newStatus.name());
        workOrder.setClosedAt(LocalDateTime.now());
        workOrderMapper.update(workOrder);

        auditLogService.log("WorkOrder", workOrderId, "CLOSE",
                request.getOperatorId(), request.getOperatorName(), oldStatus, newStatus.name(), null);
    }

    @Override
    @Transactional
    public void cancel(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder workOrder = getById(workOrderId);
        String oldStatus = workOrder.getStatus();
        WorkOrderStatus newStatus = fireTransition(workOrder, WorkOrderEvent.CANCEL);

        // 释放维修人员任务数
        if (workOrder.getAssignedWorkerId() != null) {
            repairWorkerMapper.decrementCurrentTasks(workOrder.getAssignedWorkerId());
        }

        dispatchRecordMapper.cancelByWorkOrderId(workOrderId);

        workOrder.setStatus(newStatus.name());
        workOrder.setRemark("取消原因: " + request.getRemark());
        workOrderMapper.update(workOrder);

        // 恢复设备状态
        equipmentMapper.updateStatus(workOrder.getEquipmentId(), EquipmentStatus.NORMAL.getCode());

        auditLogService.log("WorkOrder", workOrderId, "CANCEL",
                request.getOperatorId(), request.getOperatorName(), oldStatus, newStatus.name(), request.getRemark());
    }

    @Override
    @Transactional
    public WorkOrder reopen(Long workOrderId, WorkOrderActionRequest request) {
        WorkOrder original = getById(workOrderId);
        fireTransition(original, WorkOrderEvent.REOPEN);

        // 创建新工单关联原工单
        WorkOrder newOrder = new WorkOrder();
        newOrder.setOrderNo(new com.factory.repair.util.SnowflakeIdGenerator().generateOrderNo());
        newOrder.setEquipmentId(original.getEquipmentId());
        newOrder.setFaultTypeId(original.getFaultTypeId());
        newOrder.setFaultDescription("返修: " + (request.getRemark() != null ? request.getRemark() : ""));
        newOrder.setFaultLevel(Math.min(original.getFaultLevel() + 1, 3));
        newOrder.setStatus(WorkOrderStatus.REPORTED.name());
        newOrder.setReporterId(request.getOperatorId());
        newOrder.setPriority(calculatePriority(newOrder.getFaultLevel()));
        newOrder.setReportedAt(LocalDateTime.now());
        newOrder.setParentOrderId(original.getId());
        newOrder.setVersion(0);
        workOrderMapper.insert(newOrder);

        equipmentMapper.updateStatus(original.getEquipmentId(), EquipmentStatus.FAULT_STOPPED.getCode());

        auditLogService.log("WorkOrder", newOrder.getId(), "REOPEN",
                request.getOperatorId(), request.getOperatorName(), null,
                WorkOrderStatus.REPORTED.name(), "返修关联原工单: " + original.getOrderNo());

        return newOrder;
    }

    @Override
    @Transactional
    public void applySparePart(Long workOrderId, SparePartApplyRequest request, Long operatorId) {
        WorkOrder workOrder = getById(workOrderId);
        fireTransition(workOrder, WorkOrderEvent.APPLY_SPARE_PART);
        // 备件占用逻辑委托给 SparePartReservationService，由Controller层协调调用
    }

    private WorkOrderStatus fireTransition(WorkOrder workOrder, WorkOrderEvent event) {
        WorkOrderStatus currentStatus = WorkOrderStatus.valueOf(workOrder.getStatus());
        return stateMachine.fire(currentStatus, event);
    }

    private int calculatePriority(int faultLevel) {
        return switch (faultLevel) {
            case 3 -> 10;
            case 2 -> 50;
            default -> 100;
        };
    }

    private WorkOrderDTO toDTO(WorkOrder wo) {
        WorkOrderDTO dto = new WorkOrderDTO();
        dto.setId(wo.getId());
        dto.setOrderNo(wo.getOrderNo());
        dto.setEquipmentId(wo.getEquipmentId());
        dto.setFaultTypeId(wo.getFaultTypeId());
        dto.setFaultDescription(wo.getFaultDescription());
        dto.setFaultLevel(wo.getFaultLevel());
        dto.setStatus(wo.getStatus());
        dto.setStatusDescription(WorkOrderStatus.valueOf(wo.getStatus()).getDescription());
        dto.setAssignedWorkerId(wo.getAssignedWorkerId());
        dto.setPriority(wo.getPriority());
        dto.setReportedAt(wo.getReportedAt());
        dto.setDispatchedAt(wo.getDispatchedAt());
        dto.setAcceptedAt(wo.getAcceptedAt());
        dto.setArrivedAt(wo.getArrivedAt());
        dto.setCompletedAt(wo.getCompletedAt());
        dto.setClosedAt(wo.getClosedAt());
        dto.setParentOrderId(wo.getParentOrderId());
        dto.setRemark(wo.getRemark());

        Equipment eq = equipmentMapper.selectById(wo.getEquipmentId());
        if (eq != null) {
            dto.setEquipmentName(eq.getEquipmentName());
            dto.setEquipmentCode(eq.getEquipmentCode());
        }
        FaultType ft = faultTypeMapper.selectById(wo.getFaultTypeId());
        if (ft != null) {
            dto.setFaultName(ft.getFaultName());
        }
        if (wo.getAssignedWorkerId() != null) {
            RepairWorker worker = repairWorkerMapper.selectById(wo.getAssignedWorkerId());
            if (worker != null) {
                dto.setAssignedWorkerName(worker.getWorkerName());
            }
        }
        return dto;
    }
}
