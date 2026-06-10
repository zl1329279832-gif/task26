package com.factory.repair.model.enums;

public enum EquipmentStatus {
    NORMAL(0, "正常"),
    FAULT_STOPPED(1, "故障停机"),
    REPAIRING(2, "维修中"),
    SCRAPPED(3, "报废");

    private final int code;
    private final String description;

    EquipmentStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() { return code; }
    public String getDescription() { return description; }

    public static EquipmentStatus fromCode(int code) {
        for (EquipmentStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("Unknown equipment status code: " + code);
    }
}
