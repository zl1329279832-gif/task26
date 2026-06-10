package com.factory.repair.model.enums;

public enum WorkOrderStatus {
    REPORTED("已上报"),
    DISPATCHED("已派工"),
    ACCEPTED("已接单"),
    ARRIVED("已到场"),
    REPAIRING("维修中"),
    PAUSED("已暂停"),
    COMPLETED("已完工"),
    CLOSED("已关闭"),
    CANCELLED("已取消");

    private final String description;

    WorkOrderStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
