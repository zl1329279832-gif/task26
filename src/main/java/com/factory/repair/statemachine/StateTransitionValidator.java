package com.factory.repair.statemachine;

import com.factory.repair.exception.BusinessException;
import com.factory.repair.model.entity.WorkOrder;
import org.springframework.stereotype.Component;

@Component
public class StateTransitionValidator {

    public void validateTransfer(WorkOrder workOrder, Long targetWorkerId) {
        if (targetWorkerId == null) {
            throw new BusinessException("转派时必须指定目标维修人员");
        }
        if (targetWorkerId.equals(workOrder.getAssignedWorkerId())) {
            throw new BusinessException("不能转派给当前维修人员");
        }
    }

    public void validateEscalate(WorkOrder workOrder) {
        if (workOrder.getFaultLevel() >= 3) {
            throw new BusinessException("故障等级已是最高级，无法继续升级");
        }
    }

    public void validateComplete(WorkOrder workOrder) {
        if (workOrder.getAssignedWorkerId() == null) {
            throw new BusinessException("工单未分配维修人员，无法完工");
        }
    }
}
