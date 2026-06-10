package com.factory.repair.service.impl;

import com.factory.repair.mapper.DowntimeRecordMapper;
import com.factory.repair.mapper.EquipmentMapper;
import com.factory.repair.model.dto.DowntimeLossDTO;
import com.factory.repair.model.entity.DowntimeRecord;
import com.factory.repair.model.entity.Equipment;
import com.factory.repair.service.DowntimeService;
import com.factory.repair.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DowntimeServiceImpl implements DowntimeService {

    private final DowntimeRecordMapper downtimeRecordMapper;
    private final EquipmentMapper equipmentMapper;

    @Override
    @Transactional
    public void startDowntime(Long equipmentId, Long workOrderId) {
        Equipment equipment = equipmentMapper.selectById(equipmentId);
        if (equipment == null) return;

        DowntimeRecord record = new DowntimeRecord();
        record.setEquipmentId(equipmentId);
        record.setWorkOrderId(workOrderId);
        record.setStartTime(LocalDateTime.now());
        record.setHourlyLoss(equipment.getHourlyLoss());
        record.setTotalLoss(BigDecimal.ZERO);
        downtimeRecordMapper.insert(record);

        log.info("停机记录开始: equipmentId={}, workOrderId={}", equipmentId, workOrderId);
    }

    @Override
    @Transactional
    public void endDowntime(Long workOrderId) {
        DowntimeRecord record = downtimeRecordMapper.selectByWorkOrderId(workOrderId);
        if (record == null || record.getEndTime() != null) return;

        LocalDateTime now = LocalDateTime.now();
        record.setEndTime(now);
        int minutes = TimeUtils.minutesBetween(record.getStartTime(), now);
        record.setDurationMinutes(minutes);
        record.setTotalLoss(calculateLoss(minutes, record.getHourlyLoss()));
        downtimeRecordMapper.update(record);

        log.info("停机记录结束: workOrderId={}, duration={}min, loss={}",
                workOrderId, minutes, record.getTotalLoss());
    }

    @Override
    @Transactional
    public void refreshOngoingDowntime() {
        List<DowntimeRecord> ongoing = downtimeRecordMapper.selectOngoing();
        for (DowntimeRecord record : ongoing) {
            int minutes = TimeUtils.minutesBetween(record.getStartTime(), LocalDateTime.now());
            record.setDurationMinutes(minutes);
            record.setTotalLoss(calculateLoss(minutes, record.getHourlyLoss()));
            downtimeRecordMapper.update(record);
        }
        if (!ongoing.isEmpty()) {
            log.debug("刷新进行中停机记录: count={}", ongoing.size());
        }
    }

    @Override
    public List<DowntimeLossDTO> getByEquipmentId(Long equipmentId) {
        List<DowntimeRecord> records = downtimeRecordMapper.selectByEquipmentId(equipmentId);
        Equipment equipment = equipmentMapper.selectById(equipmentId);

        List<DowntimeLossDTO> result = new ArrayList<>();
        for (DowntimeRecord record : records) {
            DowntimeLossDTO dto = toDTO(record, equipment);
            result.add(dto);
        }
        return result;
    }

    @Override
    public DowntimeLossDTO getStatistics(Long equipmentId) {
        List<DowntimeRecord> records = downtimeRecordMapper.selectByEquipmentId(equipmentId);
        Equipment equipment = equipmentMapper.selectById(equipmentId);

        DowntimeLossDTO dto = new DowntimeLossDTO();
        dto.setEquipmentId(equipmentId);
        if (equipment != null) {
            dto.setEquipmentName(equipment.getEquipmentName());
            dto.setHourlyLoss(equipment.getHourlyLoss());
        }

        int totalMinutes = 0;
        BigDecimal totalLoss = BigDecimal.ZERO;
        for (DowntimeRecord record : records) {
            if (record.getDurationMinutes() != null) {
                totalMinutes += record.getDurationMinutes();
            }
            if (record.getTotalLoss() != null) {
                totalLoss = totalLoss.add(record.getTotalLoss());
            }
        }
        dto.setDurationMinutes(totalMinutes);
        dto.setTotalLoss(totalLoss);
        return dto;
    }

    private BigDecimal calculateLoss(int minutes, BigDecimal hourlyLoss) {
        if (hourlyLoss == null) return BigDecimal.ZERO;
        return hourlyLoss.multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    private DowntimeLossDTO toDTO(DowntimeRecord record, Equipment equipment) {
        DowntimeLossDTO dto = new DowntimeLossDTO();
        dto.setEquipmentId(record.getEquipmentId());
        if (equipment != null) {
            dto.setEquipmentName(equipment.getEquipmentName());
        }
        dto.setWorkOrderId(record.getWorkOrderId());
        dto.setStartTime(record.getStartTime());
        dto.setEndTime(record.getEndTime());
        dto.setDurationMinutes(record.getDurationMinutes());
        dto.setHourlyLoss(record.getHourlyLoss());
        dto.setTotalLoss(record.getTotalLoss());
        dto.setOngoing(record.getEndTime() == null);
        return dto;
    }
}
