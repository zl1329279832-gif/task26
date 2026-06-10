package com.factory.repair.scheduler;

import com.factory.repair.mapper.DispatchRecordMapper;
import com.factory.repair.mapper.WorkOrderMapper;
import com.factory.repair.model.entity.DispatchRecord;
import com.factory.repair.model.entity.WorkOrder;
import com.factory.repair.model.enums.WorkOrderStatus;
import com.factory.repair.service.AutoDispatchService;
import com.factory.repair.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchTimeoutScheduler {

    private final DispatchRecordMapper dispatchRecordMapper;
    private final WorkOrderMapper workOrderMapper;
    private final AutoDispatchService autoDispatchService;
    private final NotificationService notificationService;

    @Value("${factory.dispatch.max-retry:3}")
    private int maxRetry;

    /**
     * 每5分钟扫描超时未响应的派工记录
     */
    @Scheduled(fixedRate = 300000)
    public void checkDispatchTimeout() {
        log.debug("开始扫描派工超时...");
        try {
            List<DispatchRecord> timeoutRecords = dispatchRecordMapper.selectPendingTimeout();

            // 按工单分组统计超时次数
            Map<Long, List<DispatchRecord>> groupedByOrder = new HashMap<>();
            for (DispatchRecord record : timeoutRecords) {
                groupedByOrder.computeIfAbsent(record.getWorkOrderId(), k -> new ArrayList<>()).add(record);
            }

            for (Map.Entry<Long, List<DispatchRecord>> entry : groupedByOrder.entrySet()) {
                Long workOrderId = entry.getKey();
                List<DispatchRecord> records = entry.getValue();

                // 标记超时
                for (DispatchRecord record : records) {
                    dispatchRecordMapper.updateStatus(record.getId(), "TIMEOUT", "响应超时");
                    notificationService.notifyDispatchTimeout(workOrderId, record.getWorkerId());
                }

                // 统计该工单的总超时次数
                List<DispatchRecord> allRecords = dispatchRecordMapper.selectByWorkOrderId(workOrderId);
                long timeoutCount = allRecords.stream()
                        .filter(r -> "TIMEOUT".equals(r.getDispatchStatus()))
                        .count();

                WorkOrder workOrder = workOrderMapper.selectById(workOrderId);
                if (workOrder == null) continue;

                if (timeoutCount >= maxRetry) {
                    // 超过最大重试次数，通知主管人工干预
                    log.warn("工单派工超时已达{}次，需人工干预: workOrderId={}", timeoutCount, workOrderId);
                    workOrder.setRemark("自动派工已连续" + timeoutCount + "次超时，请主管人工派工");
                    workOrderMapper.update(workOrder);
                } else {
                    // 重新自动派工，排除已超时的人员
                    List<Long> excluded = allRecords.stream()
                            .filter(r -> "TIMEOUT".equals(r.getDispatchStatus()))
                            .map(DispatchRecord::getWorkerId)
                            .toList();

                    workOrder.setStatus(WorkOrderStatus.REPORTED.name());
                    workOrder.setAssignedWorkerId(null);
                    workOrder.setAssignedCrewId(null);
                    workOrderMapper.update(workOrder);

                    autoDispatchService.autoDispatch(workOrderId, excluded);
                    log.info("派工超时后重新派工: workOrderId={}, 第{}次", workOrderId, timeoutCount + 1);
                }
            }
        } catch (Exception e) {
            log.error("扫描派工超时失败", e);
        }
    }
}
