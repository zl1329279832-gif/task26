package com.factory.repair.model.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class DowntimeLossDTO {
    private Long equipmentId;
    private String equipmentName;
    private Long workOrderId;
    private String orderNo;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private BigDecimal hourlyLoss;
    private BigDecimal totalLoss;
    private boolean ongoing;
}
