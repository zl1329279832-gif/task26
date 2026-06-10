package com.maintenance.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum FaultStatus {

    PENDING(1, "待处理"),
    PROCESSING(2, "处理中"),
    RESOLVED(3, "已解决"),
    CLOSED(4, "已关闭");

    private final int code;
    private final String desc;

    public static FaultStatus getByCode(int code) {
        for (FaultStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
