package com.maintenance.consumer;

import com.maintenance.infrastructure.queue.MaintenanceEvent;
import com.maintenance.service.AuditService;
import com.maintenance.service.DowntimeService;
import com.maintenance.service.TechnicianService;
import com.maintenance.websocket.MaintenanceWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for Event Consumers focusing on:
 * 1. Idempotency: duplicate events are skipped
 * 2. Events are handled correctly on first delivery
 * 3. Failed events can be retried (removed from processed set)
 */
@ExtendWith(MockitoExtension.class)
class ConsumerIdempotencyTest {

    @Mock private MaintenanceWebSocketHandler wsHandler;
    @Mock private TechnicianService technicianService;
    @Mock private AuditService auditService;
    @Mock private DowntimeService downtimeService;

    // ========================================================
    // TEST: DispatchEventConsumer - duplicate events are skipped
    // ========================================================
    @Test
    @DisplayName("BUG FIX: DispatchEventConsumer must skip duplicate events")
    void dispatchConsumer_skipsDuplicateEvents() {
        DispatchEventConsumer consumer = new DispatchEventConsumer(wsHandler, technicianService, auditService);

        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("dispatch-dup-001");
        event.setEventType("DISPATCH_DONE");
        event.setPayload("{\"workOrderId\":1,\"technicianId\":100,\"technicianName\":\"Test\",\"orderCode\":\"WO001\"}");

        // First delivery should be processed
        when(wsHandler.isOnline(100L)).thenReturn(true);
        consumer.handleEvent(event);
        verify(wsHandler, times(1)).sendToTechnician(eq(100L), eq("NEW_WORK_ORDER"), any());

        // Second delivery (duplicate) should be skipped
        consumer.handleEvent(event);
        // Still only 1 call (not 2)
        verify(wsHandler, times(1)).sendToTechnician(eq(100L), eq("NEW_WORK_ORDER"), any());
    }

    // ========================================================
    // TEST: PartEventConsumer - duplicate events are skipped
    // ========================================================
    @Test
    @DisplayName("BUG FIX: PartEventConsumer must skip duplicate events")
    void partConsumer_skipsDuplicateEvents() {
        PartEventConsumer consumer = new PartEventConsumer(wsHandler, auditService);

        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("part-dup-001");
        event.setEventType("PART_CONSUMED");
        event.setPayload("{\"occupationId\":1,\"workOrderId\":1,\"partId\":10,\"quantity\":2}");

        consumer.handleEvent(event);
        consumer.handleEvent(event); // duplicate

        // Audit should only be logged once
        verify(auditService, times(1)).log(
                eq("SPARE_PART"), eq("备件消耗"), eq("WORK_ORDER"), eq(1L), anyString(), anyString());
    }

    // ========================================================
    // TEST: StatusChangeConsumer - duplicate events are skipped
    // ========================================================
    @Test
    @DisplayName("BUG FIX: StatusChangeConsumer must skip duplicate events")
    void statusConsumer_skipsDuplicateEvents() {
        StatusChangeConsumer consumer = new StatusChangeConsumer(wsHandler);

        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("status-dup-001");
        event.setEventType("REPAIR_COMPLETED");
        event.setPayload("{\"workOrderId\":1,\"technicianId\":100,\"orderCode\":\"WO001\"}");

        consumer.handleEvent(event);
        consumer.handleEvent(event); // duplicate

        // Broadcast should only happen once
        verify(wsHandler, times(1)).broadcast(eq("REPAIR_COMPLETED"), any());
    }

    // ========================================================
    // TEST: EmergencyNotificationConsumer - duplicate events are skipped
    // ========================================================
    @Test
    @DisplayName("BUG FIX: EmergencyNotificationConsumer must skip duplicate events")
    void emergencyConsumer_skipsDuplicateEvents() {
        EmergencyNotificationConsumer consumer = new EmergencyNotificationConsumer(wsHandler);

        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("emergency-dup-001");
        event.setEventType("FAULT_REPORTED");
        event.setPayload("{\"faultId\":1,\"equipmentId\":10,\"faultLevel\":4,\"faultDescription\":\"test\"}");

        consumer.handleEvent(event);
        consumer.handleEvent(event); // duplicate

        // Emergency broadcast should only happen once
        verify(wsHandler, times(1)).broadcast(eq("EMERGENCY_ALERT"), any());
    }

    // ========================================================
    // TEST: DowntimeEventConsumer - duplicate events are skipped
    // ========================================================
    @Test
    @DisplayName("BUG FIX: DowntimeEventConsumer must skip duplicate events")
    void downtimeConsumer_skipsDuplicateEvents() {
        DowntimeEventConsumer consumer = new DowntimeEventConsumer(downtimeService);

        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("downtime-dup-001");
        event.setEventType("DOWNTIME_FORCE_END");
        event.setPayload("{\"equipmentId\":10,\"workOrderId\":1}");

        consumer.handleEvent(event);
        consumer.handleEvent(event); // duplicate

        // endDowntime should only be called once
        verify(downtimeService, times(1)).endDowntime(10L, 1L);
    }

    // ========================================================
    // TEST: Different eventIds are processed independently
    // ========================================================
    @Test
    @DisplayName("Different eventIds must all be processed (not deduplicated)")
    void differentEventIds_allProcessed() {
        DowntimeEventConsumer consumer = new DowntimeEventConsumer(downtimeService);

        MaintenanceEvent event1 = new MaintenanceEvent();
        event1.setEventId("dt-001");
        event1.setEventType("DOWNTIME_FORCE_END");
        event1.setPayload("{\"equipmentId\":10,\"workOrderId\":1}");

        MaintenanceEvent event2 = new MaintenanceEvent();
        event2.setEventId("dt-002");
        event2.setEventType("DOWNTIME_FORCE_END");
        event2.setPayload("{\"equipmentId\":20,\"workOrderId\":2}");

        consumer.handleEvent(event1);
        consumer.handleEvent(event2);

        verify(downtimeService, times(1)).endDowntime(10L, 1L);
        verify(downtimeService, times(1)).endDowntime(20L, 2L);
    }

    // ========================================================
    // TEST: supportsEventType returns correct values
    // ========================================================
    @Test
    @DisplayName("supportsEventType returns correct values for each consumer")
    void supportsEventType_correctRouting() {
        DispatchEventConsumer dispatchConsumer = new DispatchEventConsumer(wsHandler, technicianService, auditService);
        assertTrue(dispatchConsumer.supportsEventType("DISPATCH_DONE"));
        assertTrue(dispatchConsumer.supportsEventType("WORK_ORDER_REASSIGNED"));
        assertFalse(dispatchConsumer.supportsEventType("STATUS_CHANGED"));

        PartEventConsumer partConsumer = new PartEventConsumer(wsHandler, auditService);
        assertTrue(partConsumer.supportsEventType("PART_REQUESTED"));
        assertTrue(partConsumer.supportsEventType("PART_CONSUMED"));
        assertTrue(partConsumer.supportsEventType("PART_RELEASED"));
        assertFalse(partConsumer.supportsEventType("DISPATCH_DONE"));

        StatusChangeConsumer statusConsumer = new StatusChangeConsumer(wsHandler);
        assertTrue(statusConsumer.supportsEventType("STATUS_CHANGED"));
        assertTrue(statusConsumer.supportsEventType("REPAIR_COMPLETED"));
        assertFalse(statusConsumer.supportsEventType("PART_REQUESTED"));

        EmergencyNotificationConsumer emergencyConsumer = new EmergencyNotificationConsumer(wsHandler);
        assertTrue(emergencyConsumer.supportsEventType("EMERGENCY_ALERT"));
        assertTrue(emergencyConsumer.supportsEventType("FAULT_REPORTED"));
        assertFalse(emergencyConsumer.supportsEventType("STATUS_CHANGED"));

        DowntimeEventConsumer downtimeConsumer = new DowntimeEventConsumer(downtimeService);
        assertTrue(downtimeConsumer.supportsEventType("DOWNTIME_FORCE_END"));
        assertFalse(downtimeConsumer.supportsEventType("FAULT_REPORTED"));

        PredictiveDispatchConsumer predictiveConsumer = new PredictiveDispatchConsumer(wsHandler, auditService);
        assertTrue(predictiveConsumer.supportsEventType("DISPATCH_PLANS_GENERATED"));
        assertTrue(predictiveConsumer.supportsEventType("PARTS_PRE_OCCUPIED"));
        assertTrue(predictiveConsumer.supportsEventType("PARTS_PRE_RELEASED"));
        assertTrue(predictiveConsumer.supportsEventType("PURCHASE_SUGGESTED"));
        assertTrue(predictiveConsumer.supportsEventType("SLA_PAUSED"));
        assertTrue(predictiveConsumer.supportsEventType("SLA_RESUMED"));
        assertFalse(predictiveConsumer.supportsEventType("DISPATCH_DONE"));
    }

    // ========================================================
    // TEST: PredictiveDispatchConsumer - duplicate events are skipped
    // ========================================================
    @Test
    @DisplayName("PredictiveDispatchConsumer must skip duplicate events")
    void predictiveConsumer_skipsDuplicateEvents() {
        PredictiveDispatchConsumer consumer = new PredictiveDispatchConsumer(wsHandler, auditService);

        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("predictive-dup-001");
        event.setEventType("DISPATCH_PLANS_GENERATED");
        event.setPayload("{\"workOrderId\":1,\"faultId\":1,\"planCount\":3,\"recommendedPlanIndex\":1}");

        consumer.handleEvent(event);
        consumer.handleEvent(event); // duplicate

        // Broadcast should only happen once
        verify(wsHandler, times(1)).broadcast(eq("DISPATCH_PLANS"), any());
    }

    @Test
    @DisplayName("PredictiveDispatchConsumer handles SLA_PAUSED event")
    void predictiveConsumer_handlesSlaPaused() {
        PredictiveDispatchConsumer consumer = new PredictiveDispatchConsumer(wsHandler, auditService);

        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("sla-paused-001");
        event.setEventType("SLA_PAUSED");
        event.setPayload("{\"workOrderId\":1,\"remainingMinutes\":60,\"reason\":\"waiting for parts\"}");

        consumer.handleEvent(event);

        verify(wsHandler).broadcast(eq("SLA_WARNING"), any());
    }

    @Test
    @DisplayName("PredictiveDispatchConsumer handles PURCHASE_SUGGESTED event")
    void predictiveConsumer_handlesPurchaseSuggested() {
        PredictiveDispatchConsumer consumer = new PredictiveDispatchConsumer(wsHandler, auditService);

        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("purchase-001");
        event.setEventType("PURCHASE_SUGGESTED");
        event.setPayload("{\"workOrderId\":1,\"partId\":10,\"partCode\":\"P001\",\"partName\":\"Bearing\",\"shortage\":2,\"urgency\":\"URGENT\"}");

        consumer.handleEvent(event);

        verify(wsHandler).broadcast(eq("PURCHASE_SUGGESTION"), any());
        verify(auditService).log(eq("PURCHASE"), eq("SUGGESTION"), eq("WorkOrder"), eq(1L), eq("SYSTEM"), anyString());
    }
}
