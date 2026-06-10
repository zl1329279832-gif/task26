package com.factory.repair.model.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class DowntimeRecord {
    private Long id;
    private Long equipmentId;
    private Long workOrderId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private BigDecimal hourlyLoss;
    private BigDecimal totalLoss;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
