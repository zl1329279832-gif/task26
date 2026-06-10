package com.maintenance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maintenance.infrastructure.queue.EventConsumer;
import com.maintenance.infrastructure.queue.MaintenanceEvent;
import com.maintenance.service.DowntimeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fix: Added idempotency guard via processedEvents set.
 */
@Component
@Slf4j
public class DowntimeEventConsumer implements EventConsumer {

    private final DowntimeService downtimeService;
    private final ObjectMapper objectMapper;

    /** Fix: Track processed eventIds for idempotent handling. */
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();

    public DowntimeEventConsumer(DowntimeService downtimeService) {
        this.downtimeService = downtimeService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supportsEventType(String eventType) {
        return "DOWNTIME_FORCE_END".equals(eventType);
    }

    @Override
    public void handleEvent(MaintenanceEvent event) {
        // FIX: Idempotency guard
        if (!processedEvents.add(event.getEventId())) {
            log.info("Skipping duplicate event [{}] eventId=[{}]", event.getEventType(), event.getEventId());
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);
            Long equipmentId = getLongValue(payload, "equipmentId");
            Long workOrderId = getLongValue(payload, "workOrderId");
            log.info("强制结束停机记录(安全兜底), equipmentId={}, workOrderId={}", equipmentId, workOrderId);
            downtimeService.endDowntime(equipmentId, workOrderId);
        } catch (Exception e) {
            processedEvents.remove(event.getEventId());
            log.error("处理停机兜底事件异常, eventId={}", event.getEventId(), e);
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
