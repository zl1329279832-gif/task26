package com.maintenance.infrastructure.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class EventDispatcher {

    private final List<EventConsumer> consumers;

    public EventDispatcher(List<EventConsumer> consumers) {
        this.consumers = consumers;
    }

    public void dispatch(MaintenanceEvent event) {
        if (event == null) {
            log.warn("Received null event, skipping dispatch");
            return;
        }

        String eventType = event.getEventType();
        boolean dispatched = false;

        for (EventConsumer consumer : consumers) {
            try {
                if (consumer.supportsEventType(eventType)) {
                    log.debug("Dispatching event [{}] to consumer [{}]", eventType, consumer.getClass().getSimpleName());
                    consumer.handleEvent(event);
                    dispatched = true;
                }
            } catch (Exception e) {
                log.error("Error dispatching event [{}] to consumer [{}]: {}",
                        eventType, consumer.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        if (!dispatched) {
            log.warn("No consumer found for event type: {}", eventType);
        }
    }
}
