package com.factory.repair.service;

import com.factory.repair.model.dto.DowntimeLossDTO;
import com.factory.repair.model.entity.DowntimeRecord;

import java.util.List;

public interface DowntimeService {
    void startDowntime(Long equipmentId, Long workOrderId);
    void endDowntime(Long workOrderId);
    void refreshOngoingDowntime();
    List<DowntimeLossDTO> getByEquipmentId(Long equipmentId);
    DowntimeLossDTO getStatistics(Long equipmentId);
}
