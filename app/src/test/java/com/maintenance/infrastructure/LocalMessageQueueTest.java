package com.maintenance.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maintenance.infrastructure.queue.EventConsumer;
import com.maintenance.infrastructure.queue.EventDispatcher;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.infrastructure.queue.MaintenanceEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for LocalMessageQueue focusing on:
 * 1. Duplicate events are deduplicated (idempotent delivery)
 * 2. Unique events are all delivered
 * 3. Consumer thread processes events correctly
 */
@ExtendWith(MockitoExtension.class)
class LocalMessageQueueTest {

    @Mock private EventConsumer mockConsumer;

    private EventDispatcher eventDispatcher;
    private LocalMessageQueue queue;

    @BeforeEach
    void setUp() {
        eventDispatcher = new EventDispatcher(Collections.singletonList(mockConsumer));
        when(mockConsumer.supportsEventType(anyString())).thenReturn(true);
    }

    // ========================================================
    // TEST: Duplicate events are deduplicated
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Duplicate events with same eventId must be delivered only once")
    void duplicateEvents_areDeduplicated() throws InterruptedException {
        // Use a counter to track dispatch calls
        AtomicInteger dispatchCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            dispatchCount.incrementAndGet();
            latch.countDown();
            return null;
        }).when(mockConsumer).handleEvent(any());

        queue = new LocalMessageQueue(new ObjectMapper(), eventDispatcher);
        queue.startConsumer();

        // Publish the same event ID twice using publishWithId
        Map<String, Object> payload = new HashMap<>();
        payload.put("testKey", "testValue");

        queue.publishWithId("dedup-test-001", "TEST_EVENT", payload);
        queue.publishWithId("dedup-test-001", "TEST_EVENT", payload);

        // Wait for at least one delivery
        assertTrue(latch.await(3, TimeUnit.SECONDS), "At least one event should be delivered");
        // Small delay to let the second (duplicate) event be processed
        Thread.sleep(200);

        // Only 1 delivery should have happened (second was deduplicated)
        assertEquals(1, dispatchCount.get(),
                "Duplicate event should be delivered only once");
    }

    // ========================================================
    // TEST: Unique events are all delivered
    // ========================================================
    @Test
    @DisplayName("Unique events with different eventIds must all be delivered")
    void uniqueEvents_allDelivered() throws InterruptedException {
        int eventCount = 5;
        CountDownLatch latch = new CountDownLatch(eventCount);

        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockConsumer).handleEvent(any());

        queue = new LocalMessageQueue(new ObjectMapper(), eventDispatcher);
        queue.startConsumer();

        for (int i = 0; i < eventCount; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("index", i);
            queue.publishWithId("unique-test-" + i, "TEST_EVENT", payload);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "All " + eventCount + " unique events should be delivered");
        verify(mockConsumer, times(eventCount)).handleEvent(any());
    }

    // ========================================================
    // TEST: publish() generates unique eventIds
    // ========================================================
    @Test
    @DisplayName("publish() generates unique eventIds for each call")
    void publish_generatesUniqueEventIds() throws InterruptedException {
        int eventCount = 3;
        CountDownLatch latch = new CountDownLatch(eventCount);

        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockConsumer).handleEvent(any());

        queue = new LocalMessageQueue(new ObjectMapper(), eventDispatcher);
        queue.startConsumer();

        Map<String, Object> payload = new HashMap<>();
        payload.put("test", "value");

        // Regular publish generates unique UUIDs
        queue.publish("TEST_EVENT", payload);
        queue.publish("TEST_EVENT", payload);
        queue.publish("TEST_EVENT", payload);

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "All events with auto-generated IDs should be delivered");
        verify(mockConsumer, times(eventCount)).handleEvent(any());
    }

    // ========================================================
    // TEST: isProcessed() correctly reports state
    // ========================================================
    @Test
    @DisplayName("isProcessed() returns true after event is dispatched")
    void isProcessed_reportsCorrectly() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockConsumer).handleEvent(any());

        queue = new LocalMessageQueue(new ObjectMapper(), eventDispatcher);

        // Before starting consumer, event is not processed
        assertFalse(queue.isProcessed("check-test-001"));

        queue.startConsumer();

        Map<String, Object> payload = new HashMap<>();
        payload.put("test", "value");
        queue.publishWithId("check-test-001", "TEST_EVENT", payload);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(100); // Let the processedEventIds update

        assertTrue(queue.isProcessed("check-test-001"),
                "Event should be marked as processed after dispatch");
        assertFalse(queue.isProcessed("never-published"),
                "Never-published event should not be marked as processed");
    }
}
