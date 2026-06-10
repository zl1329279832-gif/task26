package com.maintenance.infrastructure.queue;

public interface EventConsumer {

    void handleEvent(MaintenanceEvent event);

    boolean supportsEventType(String eventType);
}
