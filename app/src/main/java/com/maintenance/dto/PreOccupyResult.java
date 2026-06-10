package com.maintenance.dto;

import com.maintenance.entity.PurchaseSuggestion;
import com.maintenance.entity.SparePartOccupation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreOccupyResult {

    private Long workOrderId;

    private List<SparePartOccupation> occupiedParts;

    private List<PurchaseSuggestion> shortageParts;

    private boolean allPartsAvailable;
}
