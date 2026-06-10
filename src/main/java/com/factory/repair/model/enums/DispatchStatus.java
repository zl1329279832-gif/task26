package com.factory.repair.model.enums;

public enum DispatchStatus {
    PENDING("待响应"),
    ACCEPTED("已接受"),
    REJECTED("已拒绝"),
    TIMEOUT("已超时"),
    CANCELLED("已取消");

    private final String description;

    DispatchStatus(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }
}
