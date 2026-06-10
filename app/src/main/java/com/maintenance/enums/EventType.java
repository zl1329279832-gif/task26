package com.maintenance.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EventType {

    FAULT_REPORTED("故障上报"),
    WORK_ORDER_CREATED("工单创建"),
    DISPATCH_DONE("派工完成"),
    STATUS_CHANGED("状态变更"),
    PART_REQUESTED("备件申请"),
    PART_CONSUMED("备件消耗"),
    PART_RELEASED("备件释放"),
    EMERGENCY_ALERT("紧急告警"),
    REPAIR_COMPLETED("维修完成"),
    WORK_ORDER_REASSIGNED("工单转派"),
    WORK_ORDER_ESCALATED("工单升级"),
    WORK_ORDER_CLOSED("工单关闭"),
    DISPATCH_PLANS_GENERATED("派工方案生成"),
    PARTS_PRE_OCCUPIED("备件预占用"),
    PARTS_PRE_RELEASED("备件预占用释放"),
    PURCHASE_SUGGESTED("采购建议"),
    SLA_PAUSED("SLA暂停"),
    SLA_RESUMED("SLA恢复");

    private final String desc;
}
