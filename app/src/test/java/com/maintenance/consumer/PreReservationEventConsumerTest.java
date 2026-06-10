package com.maintenance.consumer;

import com.maintenance.infrastructure.queue.MaintenanceEvent;
import com.maintenance.service.AuditService;
import com.maintenance.websocket.MaintenanceWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PreReservationEventConsumerTest {

    @Mock private MaintenanceWebSocketHandler wsHandler;
    @Mock private AuditService auditService;

    private PreReservationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PreReservationEventConsumer(wsHandler, auditService);
    }

    @Test
    @DisplayName("PART_PRE_RESERVED event triggers WebSocket broadcast")
    void handlePreReserved_sendsWebSocketBroadcast() {
        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("pre-res-001");
        event.setEventType("PART_PRE_RESERVED");
        event.setPayload("{\"workOrderId\":1,\"partCode\":\"P001\",\"quantity\":2}");
        event.setTimestamp(System.currentTimeMillis());

        consumer.handleEvent(event);

        verify(wsHandler, times(1)).broadcast(eq("PART_PRE_RESERVED"), any());
    }

    @Test
    @DisplayName("PART_PRE_RESERVE_FAILED event sends alert broadcast and audit log")
    void handlePreReserveFailed_sendsAlertAndAudit() {
        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("pre-res-fail-001");
        event.setEventType("PART_PRE_RESERVE_FAILED");
        event.setPayload("{\"workOrderId\":1,\"partCode\":\"P001\",\"quantity\":2,\"reason\":\"Insufficient stock\"}");
        event.setTimestamp(System.currentTimeMillis());

        consumer.handleEvent(event);

        verify(wsHandler, times(1)).broadcast(eq("PART_PRE_RESERVE_FAILED"), any());
        verify(auditService, times(1)).log(
                eq("SPARE_PART"), eq("PRE_RESERVE_FAILED"), eq("WorkOrder"), eq(1L), eq("SYSTEM"), anyString());
    }

    @Test
    @DisplayName("Duplicate events with same eventId are skipped")
    void skipsDuplicateEvents() {
        MaintenanceEvent event = new MaintenanceEvent();
        event.setEventId("pre-res-dup-001");
        event.setEventType("PART_PRE_RESERVED");
        event.setPayload("{\"workOrderId\":1,\"partCode\":\"P001\",\"quantity\":2}");
        event.setTimestamp(System.currentTimeMillis());

        consumer.handleEvent(event);
        consumer.handleEvent(event); // duplicate

        verify(wsHandler, times(1)).broadcast(eq("PART_PRE_RESERVED"), any());
    }
}
