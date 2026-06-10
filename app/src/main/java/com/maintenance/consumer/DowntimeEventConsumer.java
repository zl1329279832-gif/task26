package com.maintenance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maintenance.infrastructure.queue.EventConsumer;
import com.maintenance.infrastructure.queue.MaintenanceEvent;
import com.maintenance.service.DowntimeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class DowntimeEventConsumer implements EventConsumer {

    private final DowntimeService downtimeService;
    private final ObjectMapper objectMapper;

    public DowntimeEventConsumer(DowntimeService downtimeService) {
        this.downtimeService = downtimeService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supportsEventType(String eventType) {
        // Downtime ending is now handled directly in WorkOrderService.complete() and closeAbnormal().
        // This consumer is kept as a safety net for orphaned downtime records only.
        return "DOWNTIME_FORCE_END".equals(eventType);
    }

    @Override
    public void handleEvent(MaintenanceEvent event) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);
            Long equipmentId = getLongValue(payload, "equipmentId");
            Long workOrderId = getLongValue(payload, "workOrderId");
            log.info("强制结束停机记录(安全兜底), equipmentId={}, workOrderId={}", equipmentId, workOrderId);
            downtimeService.endDowntime(equipmentId, workOrderId);
        } catch (Exception e) {
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
