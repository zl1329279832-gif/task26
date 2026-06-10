package com.factory.repair.dispatch;

import com.factory.repair.model.entity.FaultType;
import com.factory.repair.model.entity.RepairWorker;
import com.factory.repair.model.entity.WorkOrder;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DispatchContext {
    private WorkOrder workOrder;
    private FaultType faultType;
    private List<String> requiredSkills;
    private List<RepairWorker> candidateWorkers;
    private List<Long> excludedWorkerIds;
}
