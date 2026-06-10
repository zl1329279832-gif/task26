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
     *
     * Fix: Before creating a new record, check if an active record already exists for this
     * (equipmentId, workOrderId) pair to prevent duplicates from re-entry (e.g., resume after suspend).
     */
    @Transactional
    public DowntimeRecord startDowntime(Long equipmentId, Long workOrderId, Long faultId) {
        Equipment equipment = equipmentMapper.selectById(equipmentId);
        if (equipment == null) {
            throw new BusinessException("设备不存在, equipmentId=" + equipmentId);
        }

        // FIX: Check for existing active downtime record for this work order
        // to prevent duplicate records on resume
        DowntimeRecord existingActive = downtimeRecordMapper.selectActiveByEquipmentAndWorkOrder(
                equipmentId, workOrderId);
        if (existingActive != null) {
            log.warn("Active downtime record already exists for equipment [{}], workOrder [{}], "
                            + "downtimeRecordId=[{}], returning existing",
                    equipmentId, workOrderId, existingActive.getId());
            return existingActive;
        }

        // Create downtime record
        DowntimeRecord record = new DowntimeRecord();
        record.setEquipmentId(equipmentId);
        record.setWorkOrderId(workOrderId);
        record.setFaultId(faultId);
        record.setStartTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());

        downtimeRecordMapper.insert(record);
        log.info("Downtime started for equipment [{}], workOrder [{}], downtimeRecord [{}]",
                equipmentId, workOrderId, record.getId());

        // Update equipment status to MAINTENANCE
        equipmentMapper.updateStatus(equipmentId, EquipmentStatus.MAINTENANCE.name());
        log.info("Equipment [{}] status updated to MAINTENANCE", equipmentId);

        // Record audit log
        auditService.log("DOWNTIME", "START", "Equipment", equipmentId, "SYSTEM",
                "设备停机开始, 工单ID=" + workOrderId + ", 故障ID=" + faultId);

        return record;
    }

    /**
     * End the active downtime record for an equipment and work order.
     * Sets end_time=now, calculates duration_minutes and downtime_loss,
     * and updates equipment status to RUNNING.
     *
     * Fix: Now matches by BOTH equipmentId AND workOrderId to avoid ending the wrong
     * downtime record when the same equipment has multiple active work orders
     * (e.g., after reassignment where a new work order exists for the same equipment).
     */
    @Transactional
    public DowntimeRecord endDowntime(Long equipmentId, Long workOrderId) {
        // FIX: Find the active downtime record matching BOTH equipmentId AND workOrderId
        DowntimeRecord record = downtimeRecordMapper.selectActiveByEquipmentAndWorkOrder(
                equipmentId, workOrderId);
        if (record == null) {
            log.warn("No active downtime record found for equipment [{}], workOrder [{}]",
                    equipmentId, workOrderId);
            return null;
        }

        Equipment equipment = equipmentMapper.selectById(equipmentId);
        if (equipment == null) {
            throw new BusinessException("设备不存在, equipmentId=" + equipmentId);
        }

        LocalDateTime endTime = LocalDateTime.now();
        record.setEndTime(endTime);

        // Calculate duration in minutes
        Duration duration = Duration.between(record.getStartTime(), endTime);
        long durationMinutes = duration.toMinutes();
        record.setDurationMinutes((int) durationMinutes);

        // Calculate downtime loss based on equipment's downtime_cost_per_hour
        BigDecimal downtimeLoss = calculateDowntimeLoss(equipment.getDowntimeCostPerHour(), durationMinutes);
        record.setDowntimeLoss(downtimeLoss);

        downtimeRecordMapper.updateById(record);
        log.info("Downtime ended for equipment [{}], workOrder [{}], duration={}min, loss={}",
                equipmentId, workOrderId, durationMinutes, downtimeLoss);

        // Update equipment status to RUNNING (only if no other active downtime records exist)
        DowntimeRecord otherActive = downtimeRecordMapper.selectActiveByEquipment(equipmentId);
        if (otherActive == null) {
            equipmentMapper.updateStatus(equipmentId, EquipmentStatus.RUNNING.name());
            log.info("Equipment [{}] status updated to RUNNING (no other active downtime)", equipmentId);
        } else {
            log.info("Equipment [{}] keeps MAINTENANCE status (other active downtime exists for workOrder [{}])",
                    equipmentId, otherActive.getWorkOrderId());
        }

        // Record audit log
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
     * loss = downtime_cost_per_hour * (duration_minutes / 60.0)
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
