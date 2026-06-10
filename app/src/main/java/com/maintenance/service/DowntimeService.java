package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.entity.DowntimeRecord;
import com.maintenance.entity.Equipment;
import com.maintenance.enums.EquipmentStatus;
import com.maintenance.mapper.DowntimeRecordMapper;
import com.maintenance.mapper.EquipmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class DowntimeService {

    private final DowntimeRecordMapper downtimeRecordMapper;
    private final EquipmentMapper equipmentMapper;
    private final AuditService auditService;

    public DowntimeService(DowntimeRecordMapper downtimeRecordMapper,
                           EquipmentMapper equipmentMapper,
                           AuditService auditService) {
        this.downtimeRecordMapper = downtimeRecordMapper;
        this.equipmentMapper = equipmentMapper;
        this.auditService = auditService;
    }

    /**
     * Start a downtime record for an equipment.
     * Creates a new downtime record with start_time=now and updates equipment status to MAINTENANCE.
     */
    @Transactional
    public DowntimeRecord startDowntime(Long equipmentId, Long workOrderId, Long faultId) {
        Equipment equipment = equipmentMapper.selectById(equipmentId);
        if (equipment == null) {
            throw new BusinessException("设备不存在, equipmentId=" + equipmentId);
        }

        // Check if there's already an active downtime for this work order to avoid duplicates
        DowntimeRecord existing = downtimeRecordMapper.selectActiveByEquipmentAndWorkOrder(equipmentId, workOrderId);
        if (existing != null) {
            log.info("Active downtime already exists for equipment [{}] workOrder [{}], downtimeId={}",
                    equipmentId, workOrderId, existing.getId());
            return existing;
        }

        DowntimeRecord record = new DowntimeRecord();
        record.setEquipmentId(equipmentId);
        record.setWorkOrderId(workOrderId);
        record.setFaultId(faultId);
        record.setStartTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());

        downtimeRecordMapper.insert(record);
        log.info("Downtime started for equipment [{}], workOrder [{}], downtimeRecord [{}]",
                equipmentId, workOrderId, record.getId());

        equipmentMapper.updateStatus(equipmentId, EquipmentStatus.MAINTENANCE.name());

        auditService.log("DOWNTIME", "START", "Equipment", equipmentId, "SYSTEM",
                "设备停机开始, 工单ID=" + workOrderId + ", 故障ID=" + faultId);

        return record;
    }

    /**
     * End the active downtime record for an equipment and work order.
     * Uses both equipmentId and workOrderId for precise matching to avoid ending wrong records.
     */
    @Transactional
    public DowntimeRecord endDowntime(Long equipmentId, Long workOrderId) {
        // Find by both equipmentId and workOrderId for precision
        DowntimeRecord record = downtimeRecordMapper.selectActiveByEquipmentAndWorkOrder(equipmentId, workOrderId);
        if (record == null) {
            // Fallback to equipment-only search for backward compatibility
            record = downtimeRecordMapper.selectActiveByEquipment(equipmentId);
            if (record == null) {
                log.warn("No active downtime record found for equipment [{}] workOrder [{}]",
                        equipmentId, workOrderId);
                return null;
            }
        }

        Equipment equipment = equipmentMapper.selectById(equipmentId);
        if (equipment == null) {
            throw new BusinessException("设备不存在, equipmentId=" + equipmentId);
        }

        LocalDateTime endTime = LocalDateTime.now();
        record.setEndTime(endTime);

        Duration duration = Duration.between(record.getStartTime(), endTime);
        long durationMinutes = duration.toMinutes();
        record.setDurationMinutes((int) durationMinutes);

        BigDecimal downtimeLoss = calculateDowntimeLoss(equipment.getDowntimeCostPerHour(), durationMinutes);
        record.setDowntimeLoss(downtimeLoss);

        downtimeRecordMapper.updateById(record);
        log.info("Downtime ended for equipment [{}], workOrder [{}], duration={}min, loss={}",
                equipmentId, workOrderId, durationMinutes, downtimeLoss);

        // Only set equipment to RUNNING if no other active downtime records exist
        DowntimeRecord otherActive = downtimeRecordMapper.selectActiveByEquipment(equipmentId);
        if (otherActive == null) {
            equipmentMapper.updateStatus(equipmentId, EquipmentStatus.RUNNING.name());
            log.info("Equipment [{}] status updated to RUNNING (no more active downtimes)", equipmentId);
        } else {
            log.info("Equipment [{}] still has active downtime record [{}], keeping MAINTENANCE status",
                    equipmentId, otherActive.getId());
        }

        auditService.log("DOWNTIME", "END", "Equipment", equipmentId, "SYSTEM",
                "设备停机结束, 工单ID=" + workOrderId + ", 停机时长=" + durationMinutes + "分钟, 损失=" + downtimeLoss);

        return record;
    }

    /**
     * Calculate total downtime loss for an equipment.
     */
    public BigDecimal calculateLoss(Long equipmentId, LocalDateTime start, LocalDateTime end) {
        return downtimeRecordMapper.sumLossByEquipment(equipmentId);
    }

    /**
     * Get all downtime records for an equipment.
     */
    public List<DowntimeRecord> getByEquipment(Long equipmentId) {
        return downtimeRecordMapper.selectByEquipmentId(equipmentId);
    }

    /**
     * Calculate downtime loss based on hourly cost and duration in minutes.
     */
    private BigDecimal calculateDowntimeLoss(BigDecimal costPerHour, long durationMinutes) {
        if (costPerHour == null || costPerHour.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal hours = BigDecimal.valueOf(durationMinutes)
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        return costPerHour.multiply(hours).setScale(2, RoundingMode.HALF_UP);
    }
}
