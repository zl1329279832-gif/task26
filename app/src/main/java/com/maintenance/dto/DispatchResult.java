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
public class DispatchResult {

    private Long workOrderId;

    private String orderCode;

    private Long technicianId;

    private String technicianName;

    private BigDecimal dispatchScore;

    private String dispatchType;

    private String message;

    private boolean success;

    public static DispatchResult success(Long workOrderId, String orderCode, Long technicianId,
                                          String technicianName, BigDecimal dispatchScore, String dispatchType) {
        return DispatchResult.builder()
                .workOrderId(workOrderId)
                .orderCode(orderCode)
                .technicianId(technicianId)
                .technicianName(technicianName)
                .dispatchScore(dispatchScore)
                .dispatchType(dispatchType)
                .message("派工成功")
                .success(true)
                .build();
    }

    public static DispatchResult fail(String message) {
        return DispatchResult.builder()
                .message(message)
                .success(false)
                .build();
    }
}
