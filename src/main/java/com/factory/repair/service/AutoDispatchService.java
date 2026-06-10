package com.factory.repair.service;

import com.factory.repair.model.dto.DispatchResultDTO;

import java.util.List;

public interface AutoDispatchService {
    DispatchResultDTO autoDispatch(Long workOrderId, List<Long> excludedWorkerIds);
    void handleUrgentPreemption(Long workOrderId);
}
