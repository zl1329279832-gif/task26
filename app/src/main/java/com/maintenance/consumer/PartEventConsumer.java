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
public class PartEventConsumer implements EventConsumer {

    private final MaintenanceWebSocketHandler wsHandler;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** Fix: Track processed eventIds for idempotent handling. */
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();
    private static final int MAX_PROCESSED_EVENTS = 5000;

    public PartEventConsumer(MaintenanceWebSocketHandler wsHandler, AuditService auditService) {
        this.wsHandler = wsHandler;
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supportsEventType(String eventType) {
        return "PART_REQUESTED".equals(eventType)
                || "PART_CONSUMED".equals(eventType)
                || "PART_RELEASED".equals(eventType);
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
                case "PART_REQUESTED":
                    handlePartRequested(event);
                    break;
                case "PART_CONSUMED":
                    handlePartConsumed(event);
                    break;
                case "PART_RELEASED":
                    handlePartReleased(event);
                    break;
                default:
                    log.warn("未处理的备件事件类型: {}", eventType);
            }
        } catch (Exception e) {
            processedEvents.remove(event.getEventId());
            log.error("处理备件事件异常, eventId={}", event.getEventId(), e);
        }
    }

    private void handlePartRequested(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long workOrderId = getLongValue(payload, "workOrderId");
        Long partId = getLongValue(payload, "partId");
        Long technicianId = getLongValue(payload, "technicianId");
        Integer quantity = (Integer) payload.get("quantity");
        String partName = (String) payload.get("partName");

        log.info("备件占用请求, workOrderId={}, partId={}, quantity={}", workOrderId, partId, quantity);

        // 通知相关技术员备件已占用
        if (technicianId != null) {
            Map<String, Object> notifyData = new HashMap<>();
            notifyData.put("workOrderId", workOrderId);
            notifyData.put("partId", partId);
            notifyData.put("partName", partName);
            notifyData.put("quantity", quantity);
            notifyData.put("message", "备件已占用成功");

            wsHandler.sendToTechnician(technicianId, "PART_REQUESTED", notifyData);
        }

        // 记录审计日志
        auditService.log("SPARE_PART", "备件占用", "WORK_ORDER", workOrderId,
                "SYSTEM", "备件ID=" + partId + ", 数量=" + quantity);
    }

    private void handlePartConsumed(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long occupationId = getLongValue(payload, "occupationId");
        Long workOrderId = getLongValue(payload, "workOrderId");
        Long partId = getLongValue(payload, "partId");
        Integer quantity = (Integer) payload.get("quantity");
        String partName = (String) payload.get("partName");

        log.info("备件已消耗, occupationId={}, workOrderId={}, partId={}, quantity={}",
                occupationId, workOrderId, partId, quantity);

        // 记录备件消耗审计
        auditService.log("SPARE_PART", "备件消耗", "WORK_ORDER", workOrderId,
                "SYSTEM", "备件ID=" + partId + ", 名称=" + partName + ", 消耗数量=" + quantity);
    }

    private void handlePartReleased(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long workOrderId = getLongValue(payload, "workOrderId");
        Long partId = getLongValue(payload, "partId");
        Long technicianId = getLongValue(payload, "technicianId");
        Integer quantity = (Integer) payload.get("quantity");
        String partName = (String) payload.get("partName");

        log.info("备件已释放, workOrderId={}, partId={}, quantity={}", workOrderId, partId, quantity);

        // 记录备件释放审计
        auditService.log("SPARE_PART", "备件释放", "WORK_ORDER", workOrderId,
                "SYSTEM", "备件ID=" + partId + ", 名称=" + partName + ", 释放数量=" + quantity);

        // 通知相关人员备件已释放
        if (technicianId != null) {
            Map<String, Object> notifyData = new HashMap<>();
            notifyData.put("workOrderId", workOrderId);
            notifyData.put("partId", partId);
            notifyData.put("partName", partName);
            notifyData.put("quantity", quantity);
            notifyData.put("message", "备件已释放");

            wsHandler.sendToTechnician(technicianId, "PART_RELEASED", notifyData);
        }
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
