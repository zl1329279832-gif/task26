package com.maintenance.infrastructure.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class EventDispatcher {

    private static final long DEDUP_TTL_MS = 3600_000L; // 1 hour TTL for dedup entries
    private static final int DEDUP_CLEANUP_THRESHOLD = 10_000; // cleanup when map exceeds this size

    private final List<EventConsumer> consumers;
    private final ConcurrentHashMap<String, Long> processedEvents = new ConcurrentHashMap<>();

    public EventDispatcher(List<EventConsumer> consumers) {
        this.consumers = consumers;
    }

    public void dispatch(MaintenanceEvent event) {
        if (event == null) {
            log.warn("Received null event, skipping dispatch");
            return;
        }

        String eventId = event.getEventId();
        String eventType = event.getEventType();

        // Idempotency check: skip if this event was already processed
        if (eventId != null && processedEvents.putIfAbsent(eventId, System.currentTimeMillis()) != null) {
            log.warn("Duplicate event detected and skipped: eventId={}, eventType={}", eventId, eventType);
            return;
        }

        // Periodic cleanup of stale dedup entries
        if (processedEvents.size() > DEDUP_CLEANUP_THRESHOLD) {
            cleanupStaleEntries();
        }

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

    /**
     * Check if an event has already been processed (for external callers needing dedup checks).
     */
    public boolean isProcessed(String eventId) {
        return eventId != null && processedEvents.containsKey(eventId);
    }

    /**
     * Remove stale entries older than DEDUP_TTL_MS.
     */
    private void cleanupStaleEntries() {
        long cutoff = System.currentTimeMillis() - DEDUP_TTL_MS;
        Iterator<Map.Entry<String, Long>> it = processedEvents.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            if (it.next().getValue() < cutoff) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Cleaned up {} stale dedup entries, remaining={}", removed, processedEvents.size());
        }
    }
}
