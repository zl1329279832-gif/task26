package com.maintenance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maintenance.infrastructure.queue.EventConsumer;
import com.maintenance.infrastructure.queue.MaintenanceEvent;
import com.maintenance.service.AuditService;
import com.maintenance.service.TechnicianService;
import com.maintenance.websocket.MaintenanceWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles DISPATCH_DONE and WORK_ORDER_REASSIGNED events.
 *
 * Fix: Added idempotency guard via processedEvents set.
 * If the same eventId is delivered more than once (e.g., queue retry),
 * this consumer will skip duplicate processing.
 */
@Component
@Slf4j
public class DispatchEventConsumer implements EventConsumer {

    private final MaintenanceWebSocketHandler wsHandler;
    private final TechnicianService technicianService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Fix: Track processed eventIds to ensure idempotent handling.
     */
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();
    private static final int MAX_PROCESSED_EVENTS = 5000;

    public DispatchEventConsumer(MaintenanceWebSocketHandler wsHandler,
                                  TechnicianService technicianService,
                                  AuditService auditService) {
        this.wsHandler = wsHandler;
        this.technicianService = technicianService;
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supportsEventType(String eventType) {
        return "DISPATCH_DONE".equals(eventType) || "WORK_ORDER_REASSIGNED".equals(eventType);
    }

    @Override
    public void handleEvent(MaintenanceEvent event) {
        // FIX: Idempotency guard - skip if already processed
        if (!processedEvents.add(event.getEventId())) {
            log.info("Skipping duplicate event [{}] eventId=[{}]", event.getEventType(), event.getEventId());
            return;
        }
        // FIX: Evict old entries to prevent unbounded memory growth
        evictIfNeeded();

        try {
            String eventType = event.getEventType();
            if ("DISPATCH_DONE".equals(eventType)) {
                handleDispatchDone(event);
            } else if ("WORK_ORDER_REASSIGNED".equals(eventType)) {
                handleWorkOrderReassigned(event);
            }
        } catch (Exception e) {
            // FIX: Remove from processed set on failure so it can be retried
            processedEvents.remove(event.getEventId());
            log.error("处理派工事件异常, eventId={}", event.getEventId(), e);
        }
    }

    private void handleDispatchDone(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long technicianId = getLongValue(payload, "technicianId");
        Long workOrderId = getLongValue(payload, "workOrderId");
        String orderCode = (String) payload.get("orderCode");
        String technicianName = (String) payload.get("technicianName");

        log.info("处理派工完成事件, workOrderId={}, technicianId={}, technicianName={}",
                workOrderId, technicianId, technicianName);

        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("workOrderId", workOrderId);
        notifyData.put("orderCode", orderCode);
        notifyData.put("technicianId", technicianId);
        notifyData.put("technicianName", technicianName);

        if (wsHandler.isOnline(technicianId)) {
            wsHandler.sendToTechnician(technicianId, "NEW_WORK_ORDER", notifyData);
            log.info("派工通知已送达, technicianId={}, workOrderId={}", technicianId, workOrderId);
        } else {
            log.warn("派工通知未送达-技术员离线, technicianId={}, workOrderId={}", technicianId, workOrderId);
            auditService.log("DISPATCH", "派工通知未送达-技术员离线", "WORK_ORDER", workOrderId,
                    "SYSTEM", "技术员" + technicianId + "离线, 派工通知未送达");
        }
    }

    private void handleWorkOrderReassigned(MaintenanceEvent event) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

        Long workOrderId = getLongValue(payload, "workOrderId");
        Long newTechnicianId = getLongValue(payload, "newTechnicianId");
        Long originalTechnicianId = getLongValue(payload, "oldTechnicianId");
        String reason = (String) payload.get("reason");
        String orderCode = (String) payload.get("orderCode");

        log.info("处理工单转派事件, workOrderId={}, 原技术员={}, 新技术员={}, reason={}",
                workOrderId, originalTechnicianId, newTechnicianId, reason);

        // 通知新技术员有新工单
        if (newTechnicianId != null) {
            Map<String, Object> newTechnicianNotify = new HashMap<>();
            newTechnicianNotify.put("workOrderId", workOrderId);
            newTechnicianNotify.put("orderCode", orderCode);
            newTechnicianNotify.put("reason", reason);
            newTechnicianNotify.put("reassigned", true);

            if (wsHandler.isOnline(newTechnicianId)) {
                wsHandler.sendToTechnician(newTechnicianId, "NEW_WORK_ORDER", newTechnicianNotify);
                log.info("转派通知已送达新技术员, technicianId={}, workOrderId={}", newTechnicianId, workOrderId);
            } else {
                log.warn("转派通知未送达-新技术员离线, technicianId={}, workOrderId={}", newTechnicianId, workOrderId);
                auditService.log("DISPATCH", "派工通知未送达-技术员离线", "WORK_ORDER", workOrderId,
                        "SYSTEM", "新技术员" + newTechnicianId + "离线, 转派通知未送达");
            }
        }

        // 通知原技术员已被转派
        if (originalTechnicianId != null) {
            Map<String, Object> originalTechnicianNotify = new HashMap<>();
            originalTechnicianNotify.put("workOrderId", workOrderId);
            originalTechnicianNotify.put("orderCode", orderCode);
            originalTechnicianNotify.put("reason", reason);
            originalTechnicianNotify.put("newTechnicianId", newTechnicianId);

            if (wsHandler.isOnline(originalTechnicianId)) {
                wsHandler.sendToTechnician(originalTechnicianId, "WORK_ORDER_REASSIGNED", originalTechnicianNotify);
                log.info("转派通知已发送原技术员, technicianId={}, workOrderId={}", originalTechnicianId, workOrderId);
            }
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

    private void evictIfNeeded() {
        if (processedEvents.size() > MAX_PROCESSED_EVENTS) {
            var it = processedEvents.iterator();
            int toRemove = processedEvents.size() / 2;
            for (int i = 0; i < toRemove && it.hasNext(); i++) { it.next(); it.remove(); }
        }
    }
}
