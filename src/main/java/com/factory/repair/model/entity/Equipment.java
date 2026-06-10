package com.factory.repair.model.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Equipment {
    private Long id;
    private String equipmentCode;
    private String equipmentName;
    private String equipmentType;
    private String location;
    private Integer status;
    private BigDecimal hourlyLoss;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
