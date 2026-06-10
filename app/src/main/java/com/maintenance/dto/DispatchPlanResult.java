package com.maintenance.dto;

import com.maintenance.entity.DispatchPlan;
import com.maintenance.entity.PurchaseSuggestion;
import com.maintenance.entity.SlaRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchPlanResult {

    private Long workOrderId;

    private String orderCode;

    private List<DispatchPlan> plans;

    private Integer recommendedPlanIndex;

    private boolean partsPreOccupied;

    private List<PurchaseSuggestion> purchaseSuggestions;

    private SlaRecord slaRecord;

    private LocalDateTime slaDeadline;

    private String message;

    private boolean success;

    public static DispatchPlanResult success(Long workOrderId, String orderCode,
                                              List<DispatchPlan> plans, Integer recommendedPlanIndex,
                                              boolean partsPreOccupied, SlaRecord slaRecord) {
        return DispatchPlanResult.builder()
                .workOrderId(workOrderId)
                .orderCode(orderCode)
                .plans(plans)
                .recommendedPlanIndex(recommendedPlanIndex)
                .partsPreOccupied(partsPreOccupied)
                .slaRecord(slaRecord)
                .slaDeadline(slaRecord != null ? slaRecord.getSlaDeadline() : null)
                .message("派工方案生成成功")
                .success(true)
                .build();
    }

    public static DispatchPlanResult fail(String message) {
        return DispatchPlanResult.builder()
                .message(message)
                .success(false)
                .build();
    }
}
