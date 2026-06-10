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

@Component
@Slf4j
public class PreReservationEventConsumer implements EventConsumer {

    private final MaintenanceWebSocketHandler wsHandler;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();

    public PreReservationEventConsumer(MaintenanceWebSocketHandler wsHandler,
                                       AuditService auditService) {
        this.wsHandler = wsHandler;
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supportsEventType(String eventType) {
        return "PART_PRE_RESERVED".equals(eventType)
                || "PART_PRE_RESERVE_FAILED".equals(eventType)
                || "PURCHASE_SUGGESTION_CREATED".equals(eventType);
    }

    @Override
    public void handleEvent(MaintenanceEvent event) {
        if (!processedEvents.add(event.getEventId())) {
            log.info("Skipping duplicate event [{}] eventId=[{}]", event.getEventType(), event.getEventId());
            return;
        }

        try {
            String eventType = event.getEventType();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

            switch (eventType) {
                case "PART_PRE_RESERVED" -> handlePreReserved(payload);
                case "PART_PRE_RESERVE_FAILED" -> handlePreReserveFailed(payload);
                case "PURCHASE_SUGGESTION_CREATED" -> handlePurchaseSuggestion(payload);
            }
        } catch (Exception e) {
            processedEvents.remove(event.getEventId());
            log.error("处理备件预占用事件异常, eventId={}", event.getEventId(), e);
        }
    }

    private void handlePreReserved(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        String partCode = (String) payload.get("partCode");
        Object quantity = payload.get("quantity");

        log.info("备件预占用成功: workOrder={}, part={}, qty={}", workOrderId, partCode, quantity);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("partCode", partCode);
        notifyData.put("quantity", quantity);
        notifyData.put("action", "PRE_RESERVED");
        wsHandler.broadcast("PART_PRE_RESERVED", notifyData);
    }

    private void handlePreReserveFailed(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        String partCode = (String) payload.get("partCode");
        String reason = (String) payload.get("reason");

        log.warn("备件预占用失败: workOrder={}, part={}, reason={}", workOrderId, partCode, reason);

        Map<String, Object> alertData = new HashMap<>();
        alertData.put("workOrderId", workOrderId);
        alertData.put("partCode", partCode);
        alertData.put("reason", reason);
        alertData.put("action", "PRE_RESERVE_FAILED");
        wsHandler.broadcast("PART_PRE_RESERVE_FAILED", alertData);

        auditService.log("SPARE_PART", "PRE_RESERVE_FAILED", "WorkOrder", workOrderId, "SYSTEM",
                "Part pre-reservation failed: " + partCode + ", reason=" + reason);
    }

    private void handlePurchaseSuggestion(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        String partCode = (String) payload.get("partCode");
        Object suggestedQty = payload.get("suggestedQuantity");
        String urgency = (String) payload.get("urgency");

        log.info("采购建议生成: workOrder={}, part={}, qty={}, urgency={}",
                workOrderId, partCode, suggestedQty, urgency);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("partCode", partCode);
        notifyData.put("suggestedQuantity", suggestedQty);
        notifyData.put("urgency", urgency);
        notifyData.put("action", "PURCHASE_SUGGESTION");
        wsHandler.broadcast("PURCHASE_SUGGESTION", notifyData);
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }
}
