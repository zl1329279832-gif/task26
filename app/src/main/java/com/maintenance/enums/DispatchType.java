package com.maintenance.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DispatchType {

    AUTO(1, "自动派工"),
    MANUAL(2, "手动派工"),
    REASSIGN(3, "转派");

    private final int code;
    private final String desc;

    public static DispatchType getByCode(int code) {
        for (DispatchType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
