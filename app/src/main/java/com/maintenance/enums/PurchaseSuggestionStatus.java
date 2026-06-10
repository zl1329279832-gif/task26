package com.maintenance.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PurchaseSuggestionStatus {

    PENDING(1, "待审批"),
    APPROVED(2, "已审批"),
    ORDERED(3, "已下单"),
    FULFILLED(4, "已到货"),
    CANCELLED(5, "已取消");

    private final int code;
    private final String desc;

    public static PurchaseSuggestionStatus getByCode(int code) {
        for (PurchaseSuggestionStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
