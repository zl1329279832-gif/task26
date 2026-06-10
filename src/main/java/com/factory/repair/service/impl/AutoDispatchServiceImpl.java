package com.factory.repair.service.impl;

import com.factory.repair.dispatch.DispatchContext;
import com.factory.repair.dispatch.DispatchEngine;
import com.factory.repair.mapper.*;
import com.factory.repair.model.dto.DispatchResultDTO;
import com.factory.repair.model.entity.*;
import com.factory.repair.model.enums.WorkOrderStatus;
import com.factory.repair.service.AuditLogService;
import com.factory.repair.service.AutoDispatchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoDispatchServiceImpl implements AutoDispatchService {

    private final WorkOrderMapper workOrderMapper;
    private final FaultTypeMapper faultTypeMapper;
    private final RepairWorkerMapper repairWorkerMapper;
    private final DispatchRecordMapper dispatchRecordMapper;
    private final DispatchEngine dispatchEngine;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Value("${factory.dispatch.timeout-minutes:30}")
    private int timeoutMinutes;

    @Override
    @Transactional
    public DispatchResultDTO autoDispatch(Long workOrderId, List<Long> excludedWorkerIds) {
        WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
        if (workOrder == null) {
            return DispatchResultDTO.fail(workOrderId, "工单不存在");
        }

        FaultType faultType = faultTypeMapper.selectById(workOrder.getFaultTypeId());
        List<String> requiredSkills = parseSkills(faultType.getRequiredSkills());
        List<RepairWorker> allActive = repairWorkerMapper.selectAllActive();

        DispatchContext context = DispatchContext.builder()
                .workOrder(workOrder)
                .faultType(faultType)
                .requiredSkills(requiredSkills)
                .candidateWorkers(allActive)
                .excludedWorkerIds(excludedWorkerIds != null ? excludedWorkerIds : Collections.emptyList())
                .build();

        DispatchResultDTO result = dispatchEngine.dispatch(context);

        if (result.isSuccess()) {
            // 更新工单
            workOrder.setStatus(WorkOrderStatus.DISPATCHED.name());
            workOrder.setAssignedWorkerId(result.getWorkerId());
            workOrder.setAssignedCrewId(result.getCrewId());
            workOrder.setDispatchedAt(LocalDateTime.now());
            workOrderMapper.update(workOrder);

            // 创建派工记录
            DispatchRecord record = new DispatchRecord();
            record.setWorkOrderId(workOrderId);
            record.setWorkerId(result.getWorkerId());
            record.setCrewId(result.getCrewId());
            record.setDispatchType("AUTO");
            record.setDispatchStatus("PENDING");
            record.setScore(result.getScore());
            try {
                record.setScoreDetail(objectMapper.writeValueAsString(result.getScoreDetail()));
            } catch (Exception e) {
                log.warn("序列化评分明细失败", e);
            }
            record.setDispatchedAt(LocalDateTime.now());
            record.setTimeoutMinutes(timeoutMinutes);
            dispatchRecordMapper.insert(record);

            // 维修人员任务数+1
            repairWorkerMapper.incrementCurrentTasks(result.getWorkerId());

            auditLogService.log("WorkOrder", workOrderId, "DISPATCH",
                    null, "SYSTEM", WorkOrderStatus.REPORTED.name(),
                    WorkOrderStatus.DISPATCHED.name(),
                    "自动派工给" + result.getWorkerName() + ",评分:" + result.getScore());

            log.info("自动派工成功: orderNo={}, worker={}", workOrder.getOrderNo(), result.getWorkerName());
        } else {
            log.warn("自动派工失败: orderNo={}, reason={}", workOrder.getOrderNo(), result.getMessage());
        }

        return result;
    }

    @Override
    @Transactional
    public void handleUrgentPreemption(Long workOrderId) {
        WorkOrder urgentOrder = workOrderMapper.selectById(workOrderId);
        if (urgentOrder == null || urgentOrder.getFaultLevel() < 3) {
            return;
        }

        // 查找所有正在处理一般(1)等级工单的维修人员
        List<WorkOrder> normalOrders = workOrderMapper.selectByStatus(WorkOrderStatus.REPAIRING.name());
        List<WorkOrder> preemptable = normalOrders.stream()
                .filter(o -> o.getFaultLevel() == 1 && o.getAssignedWorkerId() != null)
                .sorted(Comparator.comparing(WorkOrder::getArrivedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        if (preemptable.isEmpty()) {
            return;
        }

        // 选择维修时间最短的工单来抢占
        WorkOrder preempted = preemptable.get(0);
        Long targetWorkerId = preempted.getAssignedWorkerId();

        // 暂停被抢占工单
        preempted.setStatus(WorkOrderStatus.PAUSED.name());
        preempted.setRemark("被紧急工单 " + urgentOrder.getOrderNo() + " 抢占");
        workOrderMapper.update(preempted);

        // 将紧急工单指派给该人员
        urgentOrder.setStatus(WorkOrderStatus.DISPATCHED.name());
        urgentOrder.setAssignedWorkerId(targetWorkerId);
        urgentOrder.setDispatchedAt(LocalDateTime.now());
        workOrderMapper.update(urgentOrder);

        // 创建派工记录
        DispatchRecord record = new DispatchRecord();
        record.setWorkOrderId(workOrderId);
        record.setWorkerId(targetWorkerId);
        record.setDispatchType("AUTO");
        record.setDispatchStatus("PENDING");
        record.setDispatchedAt(LocalDateTime.now());
        record.setTimeoutMinutes(timeoutMinutes);
        record.setRemark("紧急工单抢占");
        dispatchRecordMapper.insert(record);

        auditLogService.log("WorkOrder", workOrderId, "URGENT_PREEMPTION",
                null, "SYSTEM", null, null,
                "抢占工单" + preempted.getOrderNo() + "的维修人员");

        log.info("紧急工单抢占: urgentOrder={}, preemptedOrder={}, workerId={}",
                urgentOrder.getOrderNo(), preempted.getOrderNo(), targetWorkerId);
    }

    private List<String> parseSkills(String skillsJson) {
        if (skillsJson == null || skillsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(skillsJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
