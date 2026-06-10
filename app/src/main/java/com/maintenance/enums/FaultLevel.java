package com.maintenance.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum FaultLevel {

    MINOR(1, "一般"),
    MODERATE(2, "中等"),
    SERIOUS(3, "严重"),
    CRITICAL(4, "紧急");

    private final int code;
    private final String desc;

    public static FaultLevel getByCode(int code) {
        for (FaultLevel level : values()) {
            if (level.code == code) {
                return level;
            }
        }
        return null;
    }
}
