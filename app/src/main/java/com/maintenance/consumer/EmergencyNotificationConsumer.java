package com.maintenance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maintenance.infrastructure.queue.EventConsumer;
import com.maintenance.infrastructure.queue.MaintenanceEvent;
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
public class EmergencyNotificationConsumer implements EventConsumer {

    private final MaintenanceWebSocketHandler wsHandler;
    private final ObjectMapper objectMapper;

    /** Fix: Track processed eventIds for idempotent handling. */
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();
    private static final int MAX_PROCESSED_EVENTS = 5000;

    public EmergencyNotificationConsumer(MaintenanceWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supportsEventType(String eventType) {
        return "EMERGENCY_ALERT".equals(eventType) || "FAULT_REPORTED".equals(eventType);
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
            if ("EMERGENCY_ALERT".equals(eventType)) {
                handleEmergencyAlert(event);
            } else if ("FAULT_REPORTED".equals(eventType)) {
                handleFaultReported(event);
            }
        } catch (Exception e) {
            processedEvents.remove(event.getEventId());
            log.error("处理紧急通知事件异常, eventId={}", event.getEventId(), e);
        }
    }

    private void handleEmergencyAlert(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long faultId = getLongValue(payload, "faultId");
        Long equipmentId = getLongValue(payload, "equipmentId");
        Integer faultLevel = (Integer) payload.get("faultLevel");
        String faultDescription = (String) payload.get("faultDescription");

        log.warn("收到紧急故障提醒事件, faultId={}, equipmentId={}, faultLevel={}", faultId, equipmentId, faultLevel);

        Map<String, Object> alertData = new HashMap<>();
        alertData.put("faultId", faultId);
        alertData.put("equipmentId", equipmentId);
        alertData.put("faultLevel", faultLevel);
        alertData.put("faultDescription", faultDescription);
        alertData.put("message", "紧急故障提醒, 请立即关注!");

        wsHandler.broadcast("EMERGENCY_ALERT", alertData);
    }

    private void handleFaultReported(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long faultId = getLongValue(payload, "faultId");
        Long equipmentId = getLongValue(payload, "equipmentId");
        Integer faultLevel = (Integer) payload.get("faultLevel");
        String faultDescription = (String) payload.get("faultDescription");

        log.info("收到故障上报事件, faultId={}, equipmentId={}, faultLevel={}", faultId, equipmentId, faultLevel);

        // 若故障等级 >= 3(严重/紧急), 广播紧急提醒
        if (faultLevel != null && faultLevel >= 3) {
            log.warn("故障等级为{}, 触发紧急广播, faultId={}", faultLevel, faultId);

            Map<String, Object> alertData = new HashMap<>();
            alertData.put("faultId", faultId);
            alertData.put("equipmentId", equipmentId);
            alertData.put("faultLevel", faultLevel);
            alertData.put("faultDescription", faultDescription);
            alertData.put("message", "严重/紧急故障上报, 请相关人员立即关注!");

            wsHandler.broadcast("EMERGENCY_ALERT", alertData);
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
