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
 * Handles predictive dispatch events: DISPATCH_PLANS_GENERATED, PARTS_PRE_OCCUPIED,
 * PARTS_PRE_RELEASED, PURCHASE_SUGGESTED, SLA_PAUSED, SLA_RESUMED.
 *
 * Idempotent via processedEvents set - duplicate events are skipped.
 */
@Component
@Slf4j
public class PredictiveDispatchConsumer implements EventConsumer {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "DISPATCH_PLANS_GENERATED",
            "PARTS_PRE_OCCUPIED",
            "PARTS_PRE_RELEASED",
            "PURCHASE_SUGGESTED",
            "SLA_PAUSED",
            "SLA_RESUMED"
    );

    private final MaintenanceWebSocketHandler wsHandler;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();

    public PredictiveDispatchConsumer(MaintenanceWebSocketHandler wsHandler,
                                      AuditService auditService) {
        this.wsHandler = wsHandler;
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supportsEventType(String eventType) {
        return SUPPORTED_TYPES.contains(eventType);
    }

    @Override
    public void handleEvent(MaintenanceEvent event) {
        if (!processedEvents.add(event.getEventId())) {
            log.info("Skipping duplicate predictive dispatch event [{}] eventId=[{}]",
                    event.getEventType(), event.getEventId());
            return;
        }

        try {
            String eventType = event.getEventType();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);

            switch (eventType) {
                case "DISPATCH_PLANS_GENERATED":
                    handlePlansGenerated(payload);
                    break;
                case "PARTS_PRE_OCCUPIED":
                    handlePartsPreOccupied(payload);
                    break;
                case "PARTS_PRE_RELEASED":
                    handlePartsPreReleased(payload);
                    break;
                case "PURCHASE_SUGGESTED":
                    handlePurchaseSuggested(payload);
                    break;
                case "SLA_PAUSED":
                    handleSlaPaused(payload);
                    break;
                case "SLA_RESUMED":
                    handleSlaResumed(payload);
                    break;
                default:
                    log.warn("Unknown predictive dispatch event type: {}", eventType);
            }
        } catch (Exception e) {
            processedEvents.remove(event.getEventId());
            log.error("处理预测性派工事件异常, eventId={}", event.getEventId(), e);
        }
    }

    private void handlePlansGenerated(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        int planCount = getIntValue(payload, "planCount");
        Integer recommendedIndex = payload.get("recommendedPlanIndex") != null
                ? getIntValue(payload, "recommendedPlanIndex") : null;

        log.info("派工方案已生成, workOrderId={}, planCount={}, recommendedIndex={}",
                workOrderId, planCount, recommendedIndex);

        // Broadcast to all online supervisors
        Map<String, Object> notifyData = new HashMap<>(payload);
        wsHandler.broadcast("DISPATCH_PLANS", notifyData);
    }

    private void handlePartsPreOccupied(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        int occupiedCount = getIntValue(payload, "occupiedCount");
        int shortageCount = getIntValue(payload, "shortageCount");

        log.info("备件预占用完成, workOrderId={}, occupied={}, shortage={}",
                workOrderId, occupiedCount, shortageCount);

        wsHandler.broadcast("PARTS_PRE_OCCUPIED", payload);
    }

    private void handlePartsPreReleased(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        log.info("备件预占用已释放, workOrderId={}", workOrderId);
        wsHandler.broadcast("PARTS_PRE_RELEASED", payload);
    }

    private void handlePurchaseSuggested(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        String partCode = (String) payload.get("partCode");
        String urgency = (String) payload.get("urgency");

        log.info("采购建议已生成, workOrderId={}, partCode={}, urgency={}",
                workOrderId, partCode, urgency);

        wsHandler.broadcast("PURCHASE_SUGGESTION", payload);

        auditService.log("PURCHASE", "SUGGESTION", "WorkOrder", workOrderId, "SYSTEM",
                "Purchase suggestion: part=" + partCode + ", urgency=" + urgency);
    }

    private void handleSlaPaused(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        int remainingMinutes = getIntValue(payload, "remainingMinutes");
        String reason = (String) payload.get("reason");

        log.info("SLA已暂停, workOrderId={}, remaining={}min, reason={}",
                workOrderId, remainingMinutes, reason);

        wsHandler.broadcast("SLA_WARNING", payload);
    }

    private void handleSlaResumed(Map<String, Object> payload) {
        Long workOrderId = getLongValue(payload, "workOrderId");
        String newDeadline = (String) payload.get("newDeadline");

        log.info("SLA已恢复, workOrderId={}, newDeadline={}", workOrderId, newDeadline);

        wsHandler.broadcast("SLA_WARNING", payload);
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }

    private int getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(value.toString());
    }
}
