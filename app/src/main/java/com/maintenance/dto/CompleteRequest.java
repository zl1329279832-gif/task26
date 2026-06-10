package com.maintenance.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CompleteRequest {
    private String repairNotes;
    private BigDecimal laborCost;
}
