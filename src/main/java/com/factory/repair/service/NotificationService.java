package com.factory.repair.service;

public interface NotificationService {
    void notifyWorkOrderStatusChanged(Long workOrderId, String oldStatus, String newStatus, Long workerId);
    void notifyUrgentFault(Long workOrderId, String orderNo, String equipmentName);
    void notifyDispatchTimeout(Long workOrderId, Long workerId);
    void notifySparePartLowStock(Long sparePartId, String partName, int availableQty);
    void notifyTransfer(Long workOrderId, Long newWorkerId, String orderNo);
}
