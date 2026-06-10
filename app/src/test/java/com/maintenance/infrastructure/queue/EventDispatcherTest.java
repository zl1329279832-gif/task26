package com.maintenance.infrastructure.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventDispatcherTest {

    @Mock
    private EventConsumer consumer1;

    @Mock
    private EventConsumer consumer2;

    private EventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new EventDispatcher(Arrays.asList(consumer1, consumer2));
    }

    /**
     * Test 1: Dispatching a null event should be silently skipped with no consumer interactions.
     */
    @Test
    void dispatch_skipsNullEvent() {
        dispatcher.dispatch(null);

        verifyNoInteractions(consumer1);
        verifyNoInteractions(consumer2);
    }

    /**
     * Test 2: A consumer whose supportsEventType returns true should receive handleEvent.
     */
    @Test
    void dispatch_dispatchesToMatchingConsumer() {
        MaintenanceEvent event = new MaintenanceEvent("evt-001", "FAULT_REPORTED", "{}", System.currentTimeMillis());
        when(consumer1.supportsEventType("FAULT_REPORTED")).thenReturn(true);
        when(consumer2.supportsEventType("FAULT_REPORTED")).thenReturn(false);

        dispatcher.dispatch(event);

        verify(consumer1, times(1)).handleEvent(event);
        verify(consumer2, never()).handleEvent(any());
    }

    /**
     * Test 3: A consumer whose supportsEventType returns false should NOT receive handleEvent.
     */
    @Test
    void dispatch_skipsNonMatchingConsumer() {
        MaintenanceEvent event = new MaintenanceEvent("evt-002", "WORK_ORDER_CREATED", "{}", System.currentTimeMillis());
        when(consumer1.supportsEventType("WORK_ORDER_CREATED")).thenReturn(false);
        when(consumer2.supportsEventType("WORK_ORDER_CREATED")).thenReturn(false);

        dispatcher.dispatch(event);

        verify(consumer1, never()).handleEvent(any());
        verify(consumer2, never()).handleEvent(any());
    }

    /**
     * Test 4: Dispatching the same eventId twice should result in handleEvent being called exactly once.
     */
    @Test
    void dispatch_duplicateEventIsSkipped() {
        MaintenanceEvent event = new MaintenanceEvent("evt-003", "FAULT_REPORTED", "{\"id\":1}", System.currentTimeMillis());
        when(consumer1.supportsEventType("FAULT_REPORTED")).thenReturn(true);

        dispatcher.dispatch(event);
        dispatcher.dispatch(event);

        verify(consumer1, times(1)).handleEvent(event);
    }

    /**
     * Test 5: Events with different eventIds should both be dispatched independently.
     */
    @Test
    void dispatch_differentEventsAreNotDeduplicated() {
        MaintenanceEvent event1 = new MaintenanceEvent("evt-004", "FAULT_REPORTED", "{\"id\":1}", System.currentTimeMillis());
        MaintenanceEvent event2 = new MaintenanceEvent("evt-005", "FAULT_REPORTED", "{\"id\":2}", System.currentTimeMillis());
        when(consumer1.supportsEventType("FAULT_REPORTED")).thenReturn(true);

        dispatcher.dispatch(event1);
        dispatcher.dispatch(event2);

        verify(consumer1, times(1)).handleEvent(event1);
        verify(consumer1, times(1)).handleEvent(event2);
    }

    /**
     * Test 6: If the first consumer throws an exception, the second consumer should still receive the event.
     */
    @Test
    void dispatch_consumerExceptionDoesNotAffectOtherConsumers() {
        MaintenanceEvent event = new MaintenanceEvent("evt-006", "FAULT_REPORTED", "{}", System.currentTimeMillis());
        when(consumer1.supportsEventType("FAULT_REPORTED")).thenReturn(true);
        when(consumer2.supportsEventType("FAULT_REPORTED")).thenReturn(true);
        doThrow(new RuntimeException("Consumer 1 failed")).when(consumer1).handleEvent(event);

        dispatcher.dispatch(event);

        verify(consumer1, times(1)).handleEvent(event);
        verify(consumer2, times(1)).handleEvent(event);
    }

    /**
     * Test 7: After dispatching an event, isProcessed should return true for that eventId.
     */
    @Test
    void isProcessed_returnsTrueAfterDispatch() {
        MaintenanceEvent event = new MaintenanceEvent("evt-007", "FAULT_REPORTED", "{}", System.currentTimeMillis());
        when(consumer1.supportsEventType("FAULT_REPORTED")).thenReturn(true);

        dispatcher.dispatch(event);

        assertTrue(dispatcher.isProcessed("evt-007"));
    }

    /**
     * Test 8: isProcessed should return false for an eventId that was never dispatched.
     */
    @Test
    void isProcessed_returnsFalseForUnknownEvent() {
        assertFalse(dispatcher.isProcessed("unknown-event-id"));
    }

    /**
     * Test 9: When multiple consumers support the same event type, all of them should receive handleEvent.
     */
    @Test
    void dispatch_dispatchesToMultipleMatchingConsumers() {
        MaintenanceEvent event = new MaintenanceEvent("evt-009", "FAULT_REPORTED", "{}", System.currentTimeMillis());
        when(consumer1.supportsEventType("FAULT_REPORTED")).thenReturn(true);
        when(consumer2.supportsEventType("FAULT_REPORTED")).thenReturn(true);

        dispatcher.dispatch(event);

        verify(consumer1, times(1)).handleEvent(event);
        verify(consumer2, times(1)).handleEvent(event);
    }

    /**
     * Test 10: Two events with the same eventId but different payloads should still be deduplicated.
     */
    @Test
    void dispatch_duplicateWithDifferentPayloadsStillSkipped() {
        MaintenanceEvent event1 = new MaintenanceEvent("evt-010", "FAULT_REPORTED", "{\"version\":1}", System.currentTimeMillis());
        MaintenanceEvent event2 = new MaintenanceEvent("evt-010", "FAULT_REPORTED", "{\"version\":2}", System.currentTimeMillis());
        when(consumer1.supportsEventType("FAULT_REPORTED")).thenReturn(true);

        dispatcher.dispatch(event1);
        dispatcher.dispatch(event2);

        verify(consumer1, times(1)).handleEvent(event1);
        verify(consumer1, never()).handleEvent(event2);
    }
}
