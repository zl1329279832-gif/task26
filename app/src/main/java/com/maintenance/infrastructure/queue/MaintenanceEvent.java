package com.maintenance.infrastructure.queue;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceEvent {

    private String eventId;
    private String eventType;
    private String payload;
    private long timestamp;

    public MaintenanceEvent(String eventType, String payload) {
        this.eventId = UUID.randomUUID().toString().replace("-", "");
        this.eventType = eventType;
        this.payload = payload;
        this.timestamp = System.currentTimeMillis();
    }
}
