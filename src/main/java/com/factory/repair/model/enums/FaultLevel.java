package com.factory.repair.model.enums;

public enum FaultLevel {
    NORMAL(1, "一般"),
    IMPORTANT(2, "重要"),
    URGENT(3, "紧急");

    private final int code;
    private final String description;

    FaultLevel(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() { return code; }
    public String getDescription() { return description; }

    public static FaultLevel fromCode(int code) {
        for (FaultLevel level : values()) {
            if (level.code == code) return level;
        }
        throw new IllegalArgumentException("Unknown fault level code: " + code);
    }
}
