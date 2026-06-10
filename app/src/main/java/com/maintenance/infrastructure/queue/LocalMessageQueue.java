package com.maintenance.infrastructure.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
public class LocalMessageQueue {

    private final ConcurrentLinkedDeque<MaintenanceEvent> eventQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, BlockingQueue<MaintenanceEvent>> subscriberMap = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final EventDispatcher eventDispatcher;

    /**
     * Fix: Set of processed eventIds for idempotent delivery.
     * When the same event is delivered multiple times (e.g., from retry logic or
     * concurrent publishes), the dispatcher will skip already-processed events.
     *
     * Uses ConcurrentHashMap.newKeySet() for thread-safe concurrent access.
     *
     * Note: In a production system with high throughput, this should be replaced
     * with a bounded cache (e.g., Caffeine/Guava with TTL eviction) to prevent
     * unbounded memory growth. For this system's scale, a simple Set is sufficient.
     */
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    /**
     * Fix: Maximum number of processed eventIds to keep in memory.
     * When this limit is exceeded, the oldest entries are cleared.
     * This is a simple safeguard; a production system would use a TTL-based cache.
     */
    private static final int MAX_PROCESSED_EVENTS = 10000;

    public LocalMessageQueue(ObjectMapper objectMapper, EventDispatcher eventDispatcher) {
        this.objectMapper = objectMapper;
        this.eventDispatcher = eventDispatcher;
    }

    public void publish(String eventType, Object payload) {
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for event type [{}]: {}", eventType, e.getMessage(), e);
            return;
        }

        MaintenanceEvent event = new MaintenanceEvent(eventType, jsonPayload);
        eventQueue.addLast(event);
        log.info("Published event [{}] with eventId [{}]", eventType, event.getEventId());
    }

    /**
     * Publish with explicit eventId for deduplication.
     * If an event with the same eventId has already been processed, it will be skipped.
     * Callers can use this to ensure idempotency when retrying operations.
     */
    public void publishWithId(String eventId, String eventType, Object payload) {
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for event type [{}]: {}", eventType, e.getMessage(), e);
            return;
        }

        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId(eventId);
        event.setEventType(eventType);
        event.setPayload(jsonPayload);
        event.setTimestamp(System.currentTimeMillis());

        eventQueue.addLast(event);
        log.info("Published event [{}] with explicit eventId [{}]", eventType, eventId);
    }

    public BlockingQueue<MaintenanceEvent> subscribe(String eventType) {
        return subscriberMap.computeIfAbsent(eventType, k -> new LinkedBlockingQueue<>());
    }

    @PostConstruct
    public void startConsumer() {
        Thread consumerThread = new Thread(() -> {
            log.info("LocalMessageQueue consumer thread started");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    MaintenanceEvent event = eventQueue.pollFirst();
                    if (event == null) {
                        Thread.sleep(50);
                        continue;
                    }

                    // FIX: Idempotency check - skip already-processed events
                    String eventId = event.getEventId();
                    if (eventId != null && !processedEventIds.add(eventId)) {
                        log.info("Skipping duplicate event [{}] with eventId [{}]",
                                event.getEventType(), eventId);
                        continue;
                    }

                    // Evict old entries if the set grows too large
                    if (processedEventIds.size() > MAX_PROCESSED_EVENTS) {
                        // Simple eviction: clear half the set (oldest entries will be lost,
                        // but events that old are unlikely to be re-delivered)
                        evictOldEntries();
                    }

                    log.debug("Processing event [{}] with eventId [{}]", event.getEventType(), eventId);

                    // Dispatch to all matching consumers via EventDispatcher
                    eventDispatcher.dispatch(event);

                    // Also put into subscriber blocking queues
                    String eventType = event.getEventType();
                    BlockingQueue<MaintenanceEvent> subscriberQueue = subscriberMap.get(eventType);
                    if (subscriberQueue != null) {
                        try {
                            subscriberQueue.put(event);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Interrupted while putting event to subscriber queue");
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("LocalMessageQueue consumer thread interrupted, shutting down");
                    break;
                } catch (Exception e) {
                    log.error("Error processing event in consumer thread: {}", e.getMessage(), e);
                }
            }
            log.info("LocalMessageQueue consumer thread stopped");
        }, "event-consumer-thread");

        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    /**
     * Check if an event has already been processed.
     * Useful for consumers that want to verify idempotency independently.
     */
    public boolean isProcessed(String eventId) {
        return processedEventIds.contains(eventId);
    }

    /**
     * Simple eviction: clear the processed set when it grows too large.
     * In a production system, use a TTL-based cache (Caffeine, Guava) instead.
     */
    private void evictOldEntries() {
        int size = processedEventIds.size();
        if (size > MAX_PROCESSED_EVENTS) {
            // Clear approximately half the entries
            int toRemove = size / 2;
            var iterator = processedEventIds.iterator();
            int removed = 0;
            while (iterator.hasNext() && removed < toRemove) {
                iterator.next();
                iterator.remove();
                removed++;
            }
            log.info("Evicted {} old processed event entries, remaining={}", removed, processedEventIds.size());
        }
    }
}
