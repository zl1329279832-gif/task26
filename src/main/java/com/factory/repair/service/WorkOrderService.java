package com.factory.repair.service;

import com.factory.repair.model.dto.SparePartApplyRequest;
import com.factory.repair.model.dto.WorkOrderActionRequest;
import com.factory.repair.model.dto.WorkOrderDTO;
import com.factory.repair.model.entity.WorkOrder;

import java.util.List;

public interface WorkOrderService {
    WorkOrder getById(Long id);
    WorkOrderDTO getDetail(Long id);
    List<WorkOrderDTO> listWorkOrders(String status, Integer faultLevel, Long equipmentId);
    void accept(Long workOrderId, WorkOrderActionRequest request);
    void arrive(Long workOrderId, WorkOrderActionRequest request);
    void startRepair(Long workOrderId, WorkOrderActionRequest request);
    void pause(Long workOrderId, WorkOrderActionRequest request);
    void resume(Long workOrderId, WorkOrderActionRequest request);
    void complete(Long workOrderId, WorkOrderActionRequest request);
    void transfer(Long workOrderId, WorkOrderActionRequest request);
    void escalate(Long workOrderId, WorkOrderActionRequest request);
    void close(Long workOrderId, WorkOrderActionRequest request);
    void cancel(Long workOrderId, WorkOrderActionRequest request);
    WorkOrder reopen(Long workOrderId, WorkOrderActionRequest request);
    void applySparePart(Long workOrderId, SparePartApplyRequest request, Long operatorId);
}
