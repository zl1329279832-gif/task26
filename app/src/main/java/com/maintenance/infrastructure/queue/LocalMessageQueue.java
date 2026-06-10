package com.maintenance.infrastructure.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

                    log.debug("Processing event [{}] with eventId [{}]", event.getEventType(), event.getEventId());

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
}
