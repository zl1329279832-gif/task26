package com.maintenance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maintenance.infrastructure.queue.EventConsumer;
import com.maintenance.infrastructure.queue.MaintenanceEvent;
import com.maintenance.websocket.MaintenanceWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class StatusChangeConsumer implements EventConsumer {

    private final MaintenanceWebSocketHandler wsHandler;
    private final ObjectMapper objectMapper;

    public StatusChangeConsumer(MaintenanceWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supportsEventType(String eventType) {
        return "STATUS_CHANGED".equals(eventType)
                || "REPAIR_COMPLETED".equals(eventType)
                || "WORK_ORDER_ESCALATED".equals(eventType)
                || "WORK_ORDER_CLOSED".equals(eventType)
                || "REASSIGN_FAILED".equals(eventType)
                || "WORK_ORDER_REWORK".equals(eventType)
                || "DISPATCH_FAILED".equals(eventType);
    }

    @Override
    public void handleEvent(MaintenanceEvent event) {
        try {
            String eventType = event.getEventType();
            switch (eventType) {
                case "STATUS_CHANGED":
                    handleStatusChanged(event);
                    break;
                case "REPAIR_COMPLETED":
                    handleRepairCompleted(event);
                    break;
                case "WORK_ORDER_ESCALATED":
                    handleWorkOrderEscalated(event);
                    break;
                case "WORK_ORDER_CLOSED":
                    handleWorkOrderClosed(event);
                    break;
                case "REASSIGN_FAILED":
                    handleReassignFailed(event);
                    break;
                case "WORK_ORDER_REWORK":
                    handleWorkOrderRework(event);
                    break;
                case "DISPATCH_FAILED":
                    handleDispatchFailed(event);
                    break;
                default:
                    log.warn("未处理的事件类型: {}", eventType);
            }
        } catch (Exception e) {
            log.error("处理状态变更事件异常, eventId={}", event.getEventId(), e);
        }
    }

    private void handleStatusChanged(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long workOrderId = getLongValue(payload, "workOrderId");
        Long technicianId = getLongValue(payload, "technicianId");
        String oldStatus = (String) payload.get("oldStatus");
        String newStatus = (String) payload.get("newStatus");
        String orderCode = (String) payload.get("orderCode");

        log.info("工单状态变更, workOrderId={}, {} -> {}", workOrderId, oldStatus, newStatus);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("orderCode", orderCode);
        notifyData.put("oldStatus", oldStatus);
        notifyData.put("newStatus", newStatus);

        // 通知相关技术员
        if (technicianId != null) {
            wsHandler.sendToTechnician(technicianId, "STATUS_CHANGED", notifyData);
        }

        // 通知主管（广播给所有在线用户中标记为supervisor的角色）
        wsHandler.broadcast("STATUS_CHANGED", notifyData);
    }

    private void handleRepairCompleted(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long workOrderId = getLongValue(payload, "workOrderId");
        Long technicianId = getLongValue(payload, "technicianId");
        String orderCode = (String) payload.get("orderCode");

        log.info("工单维修完成, workOrderId={}, technicianId={}", workOrderId, technicianId);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("orderCode", orderCode);
        notifyData.put("technicianId", technicianId);
        notifyData.put("message", "工单维修已完成");

        wsHandler.broadcast("REPAIR_COMPLETED", notifyData);
    }

    private void handleWorkOrderEscalated(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long workOrderId = getLongValue(payload, "workOrderId");
        String orderCode = (String) payload.get("orderCode");
        Long technicianId = getLongValue(payload, "technicianId");
        Integer escalateCount = (Integer) payload.get("escalateCount");

        log.info("工单已升级, workOrderId={}, escalateCount={}", workOrderId, escalateCount);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("orderCode", orderCode);
        notifyData.put("technicianId", technicianId);
        notifyData.put("escalateCount", escalateCount);
        notifyData.put("message", "工单已升级, 请主管关注");

        // 广播给所有在线用户（主管会收到）
        wsHandler.broadcast("WORK_ORDER_ESCALATED", notifyData);
    }

    private void handleWorkOrderClosed(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long workOrderId = getLongValue(payload, "workOrderId");
        String orderCode = (String) payload.get("orderCode");
        Long technicianId = getLongValue(payload, "technicianId");
        String reason = (String) payload.get("reason");

        log.info("工单已关闭, workOrderId={}, reason={}", workOrderId, reason);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("orderCode", orderCode);
        notifyData.put("reason", reason);
        notifyData.put("message", "工单已异常关闭");

        if (technicianId != null) {
            wsHandler.sendToTechnician(technicianId, "WORK_ORDER_CLOSED", notifyData);
        }

        wsHandler.broadcast("WORK_ORDER_CLOSED", notifyData);
    }

    private void handleReassignFailed(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long workOrderId = getLongValue(payload, "workOrderId");
        Long oldTechnicianId = getLongValue(payload, "oldTechnicianId");
        Long newTechnicianId = getLongValue(payload, "newTechnicianId");
        String reason = (String) payload.get("reason");

        log.warn("工单转派失败, workOrderId={}, oldTech={}, newTech={}, reason={}",
                workOrderId, oldTechnicianId, newTechnicianId, reason);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("oldTechnicianId", oldTechnicianId);
        notifyData.put("newTechnicianId", newTechnicianId);
        notifyData.put("reason", reason);
        notifyData.put("message", "工单转派失败: " + reason);

        if (oldTechnicianId != null) {
            wsHandler.sendToTechnician(oldTechnicianId, "REASSIGN_FAILED", notifyData);
        }
        wsHandler.broadcast("REASSIGN_FAILED", notifyData);
    }

    private void handleWorkOrderRework(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long originalWorkOrderId = getLongValue(payload, "originalWorkOrderId");
        Long newWorkOrderId = getLongValue(payload, "newWorkOrderId");
        String newOrderCode = (String) payload.get("newOrderCode");
        String reason = (String) payload.get("reason");

        log.info("工单返工, originalId={}, newId={}, reason={}", originalWorkOrderId, newWorkOrderId, reason);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("originalWorkOrderId", originalWorkOrderId);
        notifyData.put("newWorkOrderId", newWorkOrderId);
        notifyData.put("newOrderCode", newOrderCode);
        notifyData.put("reason", reason);
        notifyData.put("message", "工单已触发返工, 新工单已创建");

        wsHandler.broadcast("WORK_ORDER_REWORK", notifyData);
    }

    private void handleDispatchFailed(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long workOrderId = getLongValue(payload, "workOrderId");
        String reason = (String) payload.get("reason");

        log.warn("派工失败, workOrderId={}, reason={}", workOrderId, reason);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("reason", reason);
        notifyData.put("message", "派工失败: " + reason);

        wsHandler.broadcast("DISPATCH_FAILED", notifyData);
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }
}
