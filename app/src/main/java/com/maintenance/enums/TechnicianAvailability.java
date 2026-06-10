package com.maintenance.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TechnicianAvailability {

    AVAILABLE(1, "可用"),
    BUSY(2, "忙碌"),
    OFFLINE(3, "离线"),
    ON_LEAVE(4, "休假");

    private final int code;
    private final String desc;

    public static TechnicianAvailability getByCode(int code) {
        for (TechnicianAvailability availability : values()) {
            if (availability.code == code) {
                return availability;
            }
        }
        return null;
    }
}
