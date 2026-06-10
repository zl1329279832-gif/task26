package com.factory.repair.model.enums;

public enum ReservationStatus {
    RESERVED("已占用"),
    CONSUMED("已消耗"),
    RELEASED("已释放");

    private final String description;

    ReservationStatus(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }
}
