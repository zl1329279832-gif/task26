package com.factory.repair.service;

import com.factory.repair.model.entity.SparePartReservation;
import java.util.List;

public interface SparePartReservationService {
    SparePartReservation reserve(Long workOrderId, Long sparePartId, int quantity);
    void release(Long reservationId, String reason);
    void releaseByWorkOrder(Long workOrderId, String reason);
    void consume(Long reservationId);
    List<SparePartReservation> getByWorkOrderId(Long workOrderId);
    void releaseExpired();
    void cleanupOrphanedReservations();
}
