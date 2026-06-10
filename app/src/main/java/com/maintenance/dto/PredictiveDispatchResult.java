package com.maintenance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictiveDispatchResult {

    private Long workOrderId;

    private String orderCode;

    private List<DispatchPlanDTO> plans;

    private boolean partsPreReserved;

    private List<PurchaseSuggestionDTO> purchaseSuggestions;

    private boolean slaPaused;

    private String message;

    private boolean success;

    private DispatchPlanDTO selectedPlan;

    private DispatchResult dispatchResult;

    public static PredictiveDispatchResult success(Long workOrderId, String orderCode,
                                                    List<DispatchPlanDTO> plans) {
        return PredictiveDispatchResult.builder()
                .workOrderId(workOrderId)
                .orderCode(orderCode)
                .plans(plans)
                .message("派工方案生成成功")
                .success(true)
                .build();
    }

    public static PredictiveDispatchResult fail(String message) {
        return PredictiveDispatchResult.builder()
                .message(message)
                .success(false)
                .build();
    }
}
