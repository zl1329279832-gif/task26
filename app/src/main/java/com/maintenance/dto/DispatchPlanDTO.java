package com.maintenance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchPlanDTO {

    private Long planId;

    private Integer rank;

    private Long technicianId;

    private String technicianName;

    private BigDecimal totalScore;

    private BigDecimal skillScore;

    private BigDecimal certScore;

    private BigDecimal availabilityScore;

    private BigDecimal workloadScore;

    private BigDecimal historyScore;

    private BigDecimal partsScore;

    private BigDecimal downtimeCostScore;

    private BigDecimal slaScore;

    private Integer estimatedRepairMinutes;

    private BigDecimal estimatedDowntimeLoss;

    private Integer slaRemainingMinutes;

    private String recommendationReason;

    private List<PartAvailabilityDTO> partsAvailability;
}
