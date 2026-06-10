package com.factory.repair.model.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FaultType {
    private Long id;
    private String faultCode;
    private String faultName;
    private String equipmentType;
    private Integer faultLevel;
    private String requiredSkills;
    private BigDecimal estimatedHours;
    private LocalDateTime createdAt;
}
