package com.factory.repair.service;

import com.factory.repair.model.dto.FaultReportRequest;
import com.factory.repair.model.entity.WorkOrder;

public interface FaultReportService {
    WorkOrder reportFault(FaultReportRequest request);
}
