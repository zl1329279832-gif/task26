package com.factory.repair.service.impl;

import com.factory.repair.mq.MessageProducer;
import com.factory.repair.mq.Topic;
import com.factory.repair.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final MessageProducer messageProducer;

    @Override
    public void notifyWorkOrderStatusChanged(Long workOrderId, String oldStatus, String newStatus, Long workerId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "WORK_ORDER_STATUS_CHANGE");
        payload.put("workOrderId", workOrderId);
        payload.put("oldStatus", oldStatus);
        payload.put("newStatus", newStatus);
        payload.put("targetUserId", workerId);
        payload.put("message", "工单状态从[" + oldStatus + "]变更为[" + newStatus + "]");
        messageProducer.publish(Topic.NOTIFICATION_EVENT, "PUSH_NOTIFICATION", payload);
    }

    @Override
    public void notifyUrgentFault(Long workOrderId, String orderNo, String equipmentName) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "URGENT_FAULT");
        payload.put("workOrderId", workOrderId);
        payload.put("orderNo", orderNo);
        payload.put("equipmentName", equipmentName);
        payload.put("message", "紧急故障: 设备[" + equipmentName + "]发生紧急故障，工单号: " + orderNo);
        messageProducer.publish(Topic.NOTIFICATION_EVENT, "PUSH_NOTIFICATION", payload);
    }

    @Override
    public void notifyDispatchTimeout(Long workOrderId, Long workerId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "DISPATCH_TIMEOUT");
        payload.put("workOrderId", workOrderId);
        payload.put("workerId", workerId);
        payload.put("message", "派工超时: 维修人员未在规定时间内响应");
        messageProducer.publish(Topic.NOTIFICATION_EVENT, "PUSH_NOTIFICATION", payload);
    }

    @Override
    public void notifySparePartLowStock(Long sparePartId, String partName, int availableQty) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "SPARE_PART_LOW");
        payload.put("sparePartId", sparePartId);
        payload.put("partName", partName);
        payload.put("availableQty", availableQty);
        payload.put("message", "备件低库存: [" + partName + "]当前可用数量: " + availableQty);
        messageProducer.publish(Topic.NOTIFICATION_EVENT, "PUSH_NOTIFICATION", payload);
    }

    @Override
    public void notifyTransfer(Long workOrderId, Long newWorkerId, String orderNo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "WORK_ORDER_TRANSFER");
        payload.put("workOrderId", workOrderId);
        payload.put("targetUserId", newWorkerId);
        payload.put("orderNo", orderNo);
        payload.put("message", "工单[" + orderNo + "]已转派给您");
        messageProducer.publish(Topic.NOTIFICATION_EVENT, "PUSH_NOTIFICATION", payload);
    }
}
