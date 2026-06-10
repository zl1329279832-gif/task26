package com.maintenance.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DispatchPlanStatus {

    PENDING(1, "待选择"),
    SELECTED(2, "已选中"),
    REJECTED(3, "已拒绝"),
    EXPIRED(4, "已过期");

    private final int code;
    private final String desc;

    public static DispatchPlanStatus getByCode(int code) {
        for (DispatchPlanStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
