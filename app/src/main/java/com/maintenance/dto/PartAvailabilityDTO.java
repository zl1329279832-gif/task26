package com.maintenance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartAvailabilityDTO {

    private Long partId;

    private String partCode;

    private String partName;

    private Integer currentStock;

    private Integer requiredQuantity;

    private boolean sufficient;
}
