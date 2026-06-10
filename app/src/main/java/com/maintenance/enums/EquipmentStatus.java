package com.maintenance.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EquipmentStatus {

    RUNNING(1, "运行中"),
    FAULT(2, "故障"),
    MAINTENANCE(3, "维修中"),
    STOPPED(4, "停机");

    private final int code;
    private final String desc;

    public static EquipmentStatus getByCode(int code) {
        for (EquipmentStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
