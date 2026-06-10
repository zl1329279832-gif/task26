package com.maintenance.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

@Getter
@AllArgsConstructor
public enum WorkOrderStatus {

    CREATED(0, "已创建"),
    ACCEPTED(1, "已接单"),
    ARRIVED(2, "已到场"),
    REPAIRING(3, "维修中"),
    COMPLETED(4, "已完工"),
    SUSPENDED(5, "已暂停"),
    REASSIGNED(6, "已转派"),
    CLOSED_ABNORMAL(7, "异常关闭");

    private final int code;
    private final String desc;

    public static WorkOrderStatus getByCode(int code) {
        for (WorkOrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    public boolean canTransitionTo(WorkOrderStatus target) {
        Set<WorkOrderStatus> allowed = getAllowedTransitions();
        return allowed.contains(target);
    }

    private Set<WorkOrderStatus> getAllowedTransitions() {
        return switch (this) {
            case CREATED -> Set.of(ACCEPTED, REASSIGNED, CLOSED_ABNORMAL);
            case ACCEPTED -> Set.of(ARRIVED, REASSIGNED, SUSPENDED, CLOSED_ABNORMAL);
            case ARRIVED -> Set.of(REPAIRING, SUSPENDED, REASSIGNED, CLOSED_ABNORMAL);
            case REPAIRING -> Set.of(COMPLETED, SUSPENDED, REASSIGNED, CLOSED_ABNORMAL);
            case SUSPENDED -> Set.of(REPAIRING, ARRIVED, REASSIGNED, CLOSED_ABNORMAL);
            case COMPLETED -> Set.of(CREATED, CLOSED_ABNORMAL);
            case REASSIGNED -> Set.of();
            case CLOSED_ABNORMAL -> Set.of();
        };
    }
}
