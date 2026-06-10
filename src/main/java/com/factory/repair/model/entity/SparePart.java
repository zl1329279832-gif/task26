package com.factory.repair.model.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SparePart {
    private Long id;
    private String partCode;
    private String partName;
    private String equipmentType;
    private Integer totalQty;
    private Integer availableQty;
    private Integer reservedQty;
    private Integer minStock;
    private BigDecimal unitPrice;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
