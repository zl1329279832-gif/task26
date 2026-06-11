package com.maintenance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maintenance.infrastructure.queue.EventConsumer;
import com.maintenance.infrastructure.queue.MaintenanceEvent;
import com.maintenance.service.AuditService;
import com.maintenance.websocket.MaintenanceWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fix: Added idempotency guard via processedEvents set.
 */
@Component
@Slf4j
public class StatusChangeConsumer implements EventConsumer {

    private final MaintenanceWebSocketHandler wsHandler;
    private final ObjectMapper objectMapper;

    /** Fix: Track processed eventIds for idempotent handling. */
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();
    private static final int MAX_PROCESSED_EVENTS = 5000;

    public StatusChangeConsumer(MaintenanceWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supportsEventType(String eventType) {
        return "STATUS_CHANGED".equals(eventType)
                || "REPAIR_COMPLETED".equals(eventType)
                || "WORK_ORDER_ESCALATED".equals(eventType)
                || "WORK_ORDER_CLOSED".equals(eventType);
    }

    @Override
    public void handleEvent(MaintenanceEvent event) {
        // FIX: Idempotency guard
        if (!processedEvents.add(event.getEventId())) {
            log.info("Skipping duplicate event [{}] eventId=[{}]", event.getEventType(), event.getEventId());
            return;
        }
        evictIfNeeded();
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
                default:
                    log.warn("未处理的事件类型: {}", eventType);
            }
        } catch (Exception e) {
            processedEvents.remove(event.getEventId());
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

        // 通知相关技术员
        if (technicianId != null) {
            wsHandler.sendToTechnician(technicianId, "WORK_ORDER_CLOSED", notifyData);
        }

        // 广播关闭通知
        wsHandler.broadcast("WORK_ORDER_CLOSED", notifyData);
    }

    private void evictIfNeeded() {
        if (processedEvents.size() > MAX_PROCESSED_EVENTS) {
            var it = processedEvents.iterator();
            int toRemove = processedEvents.size() / 2;
            for (int i = 0; i < toRemove && it.hasNext(); i++) { it.next(); it.remove(); }
        }
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
