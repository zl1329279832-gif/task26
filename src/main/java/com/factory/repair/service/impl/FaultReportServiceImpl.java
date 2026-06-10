package com.factory.repair.service.impl;

import com.factory.repair.exception.BusinessException;
import com.factory.repair.exception.DuplicateReportException;
import com.factory.repair.mapper.EquipmentMapper;
import com.factory.repair.mapper.FaultTypeMapper;
import com.factory.repair.mapper.WorkOrderMapper;
import com.factory.repair.model.dto.FaultReportRequest;
import com.factory.repair.model.entity.Equipment;
import com.factory.repair.model.entity.FaultType;
import com.factory.repair.model.entity.WorkOrder;
import com.factory.repair.model.enums.EquipmentStatus;
import com.factory.repair.model.enums.WorkOrderStatus;
import com.factory.repair.service.AuditLogService;
import com.factory.repair.service.FaultReportService;
import com.factory.repair.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class FaultReportServiceImpl implements FaultReportService {

    private final WorkOrderMapper workOrderMapper;
    private final EquipmentMapper equipmentMapper;
    private final FaultTypeMapper faultTypeMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${factory.duplicate-report.window-minutes:10}")
    private int duplicateWindowMinutes;

    @Override
    @Transactional
    public WorkOrder reportFault(FaultReportRequest request) {
        Equipment equipment = equipmentMapper.selectById(request.getEquipmentId());
        if (equipment == null) {
            throw new BusinessException("设备不存在");
        }

        FaultType faultType = faultTypeMapper.selectById(request.getFaultTypeId());
        if (faultType == null) {
            throw new BusinessException("故障类型不存在");
        }

        // 重复上报检测
        String dedupKey = "equipment:recent_fault:" + request.getEquipmentId() + ":" + request.getFaultTypeId();
        Boolean isDuplicate = stringRedisTemplate.hasKey(dedupKey);

        List<WorkOrder> activeOrders = workOrderMapper.selectActiveByEquipmentAndFaultType(
                request.getEquipmentId(), request.getFaultTypeId());
        if (!activeOrders.isEmpty()) {
            throw new DuplicateReportException(activeOrders.get(0).getOrderNo());
        }

        // 创建工单
        WorkOrder workOrder = new WorkOrder();
        workOrder.setOrderNo(idGenerator.generateOrderNo());
        workOrder.setEquipmentId(request.getEquipmentId());
        workOrder.setFaultTypeId(request.getFaultTypeId());
        workOrder.setFaultDescription(request.getFaultDescription());
        workOrder.setFaultLevel(faultType.getFaultLevel());
        workOrder.setStatus(WorkOrderStatus.REPORTED.name());
        workOrder.setReporterId(request.getReporterId());
        workOrder.setPriority(calculatePriority(faultType.getFaultLevel()));
        workOrder.setReportedAt(LocalDateTime.now());
        workOrder.setVersion(0);

        if (Boolean.TRUE.equals(isDuplicate)) {
            workOrder.setRemark("疑似重复上报，请核实");
        }

        workOrderMapper.insert(workOrder);

        // 设置重复上报检测窗口
        stringRedisTemplate.opsForValue().set(dedupKey, workOrder.getOrderNo(),
                duplicateWindowMinutes, TimeUnit.MINUTES);

        // 更新设备状态
        equipmentMapper.updateStatus(equipment.getId(), EquipmentStatus.FAULT_STOPPED.getCode());

        auditLogService.log("WorkOrder", workOrder.getId(), "CREATE",
                request.getReporterId(), null, null, workOrder.getOrderNo(), null);

        log.info("故障上报成功: orderNo={}, equipmentId={}, faultLevel={}",
                workOrder.getOrderNo(), request.getEquipmentId(), faultType.getFaultLevel());

        return workOrder;
    }

    private int calculatePriority(int faultLevel) {
        return switch (faultLevel) {
            case 3 -> 10;   // 紧急
            case 2 -> 50;   // 重要
            default -> 100; // 一般
        };
    }
}
