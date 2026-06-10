package com.maintenance.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OccupationStatus {

    OCCUPIED(1, "已占用"),
    CONSUMED(2, "已消耗"),
    RELEASED(3, "已释放"),
    PRE_RESERVED(4, "预占用");

    private final int code;
    private final String desc;

    public static OccupationStatus getByCode(int code) {
        for (OccupationStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
