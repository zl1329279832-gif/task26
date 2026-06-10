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
public class DispatchPlanEventConsumer implements EventConsumer {

    private final MaintenanceWebSocketHandler wsHandler;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();

    public DispatchPlanEventConsumer(MaintenanceWebSocketHandler wsHandler,
                                     AuditService auditService) {
        this.wsHandler = wsHandler;
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supportsEventType(String eventType) {
        return "DISPATCH_PLANS_GENERATED".equals(eventType)
                || "DISPATCH_PLAN_SELECTED".equals(eventType);
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

            if ("DISPATCH_PLANS_GENERATED".equals(eventType)) {
                handlePlansGenerated(payload);
            } else if ("DISPATCH_PLAN_SELECTED".equals(eventType)) {
                handlePlanSelected(payload);
            }
        } catch (Exception e) {
            processedEvents.remove(event.getEventId());
            log.error("处理派工方案事件异常, eventId={}", event.getEventId(), e);
        }
    }

    private void handlePlansGenerated(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        String orderCode = (String) payload.get("orderCode");
        Object planCount = payload.get("planCount");
        Object partsPreReserved = payload.get("partsPreReserved");

        log.info("派工方案已生成: workOrder={}, planCount={}, partsPreReserved={}",
                workOrderId, planCount, partsPreReserved);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("orderCode", orderCode);
        notifyData.put("planCount", planCount);
        notifyData.put("partsPreReserved", partsPreReserved);
        wsHandler.broadcast("DISPATCH_PLANS_READY", notifyData);
    }

    private void handlePlanSelected(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        String orderCode = (String) payload.get("orderCode");
        Long technicianId = getLongValue(payload, "technicianId");
        Long planId = getLongValue(payload, "planId");

        log.info("派工方案已选中: workOrder={}, planId={}, technicianId={}",
                workOrderId, planId, technicianId);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("orderCode", orderCode);
        notifyData.put("planId", planId);
        notifyData.put("technicianId", technicianId);

        // Notify the selected technician
        if (technicianId != null && wsHandler.isOnline(technicianId)) {
            wsHandler.sendToTechnician(technicianId, "NEW_WORK_ORDER", notifyData);
            log.info("派工方案通知已送达, technicianId={}, workOrderId={}", technicianId, workOrderId);
        }

        // Broadcast to supervisors
        wsHandler.broadcast("DISPATCH_PLAN_SELECTED", notifyData);
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }
}
