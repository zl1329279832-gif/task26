package com.maintenance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseSuggestionDTO {

    private Long suggestionId;

    private Long partId;

    private String partCode;

    private String partName;

    private Integer currentStock;

    private Integer requiredQuantity;

    private Integer suggestedPurchaseQuantity;

    private String urgencyLevel;

    private BigDecimal estimatedCost;
}
