package com.maintenance.service;

import com.maintenance.entity.DowntimeRecord;
import com.maintenance.entity.Equipment;
import com.maintenance.mapper.DowntimeRecordMapper;
import com.maintenance.mapper.EquipmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for DowntimeService focusing on:
 * 1. endDowntime matches by BOTH equipmentId AND workOrderId
 * 2. startDowntime prevents duplicate records
 * 3. Equipment status transitions correctly based on remaining downtime
 */
@ExtendWith(MockitoExtension.class)
class DowntimeServiceTest {

    @Mock private DowntimeRecordMapper downtimeRecordMapper;
    @Mock private EquipmentMapper equipmentMapper;
    @Mock private AuditService auditService;

    private DowntimeService downtimeService;

    @BeforeEach
    void setUp() {
        downtimeService = new DowntimeService(downtimeRecordMapper, equipmentMapper, auditService);
    }

    // ========================================================
    // TEST: endDowntime matches by workOrderId
    // ========================================================
    @Test
    @DisplayName("BUG FIX: endDowntime must match by BOTH equipmentId AND workOrderId")
    void endDowntime_matchesByWorkOrderId() {
        DowntimeRecord record = new DowntimeRecord();
        record.setId(1L);
        record.setEquipmentId(50L);
        record.setWorkOrderId(1L);
        record.setStartTime(LocalDateTime.now().minusHours(2));

        Equipment equipment = new Equipment();
        equipment.setId(50L);
        equipment.setDowntimeCostPerHour(BigDecimal.valueOf(100));

        when(downtimeRecordMapper.selectActiveByEquipmentAndWorkOrder(50L, 1L)).thenReturn(record);
        when(equipmentMapper.selectById(50L)).thenReturn(equipment);
        when(downtimeRecordMapper.updateById(any())).thenReturn(1);
        when(downtimeRecordMapper.selectActiveByEquipment(50L)).thenReturn(null); // no other active
        when(equipmentMapper.updateStatus(50L, "RUNNING")).thenReturn(1);

        DowntimeRecord result = downtimeService.endDowntime(50L, 1L);

        assertNotNull(result);
        assertNotNull(result.getEndTime());
        assertTrue(result.getDurationMinutes() >= 119, "Duration should be ~120 minutes");
        // Verify the correct query was used (with workOrderId)
        verify(downtimeRecordMapper).selectActiveByEquipmentAndWorkOrder(50L, 1L);
    }

    // ========================================================
    // TEST: endDowntime with no matching record returns null
    // ========================================================
    @Test
    @DisplayName("endDowntime returns null when no active record matches equipmentId+workOrderId")
    void endDowntime_returnsNullWhenNoMatch() {
        when(downtimeRecordMapper.selectActiveByEquipmentAndWorkOrder(50L, 99L)).thenReturn(null);

        DowntimeRecord result = downtimeService.endDowntime(50L, 99L);

        assertNull(result);
    }

    // ========================================================
    // TEST: Equipment stays MAINTENANCE if other downtime exists
    // ========================================================
    @Test
    @DisplayName("Equipment stays MAINTENANCE if other active downtime records exist")
    void endDowntime_keepsMaintenanceIfOtherActive() {
        DowntimeRecord record = new DowntimeRecord();
        record.setId(1L);
        record.setEquipmentId(50L);
        record.setWorkOrderId(1L);
        record.setStartTime(LocalDateTime.now().minusMinutes(30));

        DowntimeRecord otherActive = new DowntimeRecord();
        otherActive.setId(2L);
        otherActive.setEquipmentId(50L);
        otherActive.setWorkOrderId(2L);

        Equipment equipment = new Equipment();
        equipment.setId(50L);
        equipment.setDowntimeCostPerHour(BigDecimal.valueOf(100));

        when(downtimeRecordMapper.selectActiveByEquipmentAndWorkOrder(50L, 1L)).thenReturn(record);
        when(equipmentMapper.selectById(50L)).thenReturn(equipment);
        when(downtimeRecordMapper.updateById(any())).thenReturn(1);
        when(downtimeRecordMapper.selectActiveByEquipment(50L)).thenReturn(otherActive);

        downtimeService.endDowntime(50L, 1L);

        // Equipment should NOT be set to RUNNING because another downtime is active
        verify(equipmentMapper, never()).updateStatus(50L, "RUNNING");
    }

    // ========================================================
    // TEST: startDowntime prevents duplicate records
    // ========================================================
    @Test
    @DisplayName("BUG FIX: startDowntime returns existing record if active one already exists")
    void startDowntime_preventsDuplicateRecords() {
        DowntimeRecord existing = new DowntimeRecord();
        existing.setId(1L);
        existing.setEquipmentId(50L);
        existing.setWorkOrderId(1L);
        existing.setStartTime(LocalDateTime.now().minusHours(1));

        Equipment equipment = new Equipment();
        equipment.setId(50L);

        when(equipmentMapper.selectById(50L)).thenReturn(equipment);
        when(downtimeRecordMapper.selectActiveByEquipmentAndWorkOrder(50L, 1L)).thenReturn(existing);

        DowntimeRecord result = downtimeService.startDowntime(50L, 1L, 10L);

        // Should return existing record, not create a new one
        assertEquals(1L, result.getId());
        verify(downtimeRecordMapper, never()).insert(any());
    }

    // ========================================================
    // TEST: startDowntime creates new record when none active
    // ========================================================
    @Test
    @DisplayName("startDowntime creates new record when no active record exists")
    void startDowntime_createsNewRecord() {
        Equipment equipment = new Equipment();
        equipment.setId(50L);

        when(equipmentMapper.selectById(50L)).thenReturn(equipment);
        when(downtimeRecordMapper.selectActiveByEquipmentAndWorkOrder(50L, 1L)).thenReturn(null);
        when(downtimeRecordMapper.insert(any())).thenReturn(1);
        when(equipmentMapper.updateStatus(50L, "MAINTENANCE")).thenReturn(1);

        DowntimeRecord result = downtimeService.startDowntime(50L, 1L, 10L);

        assertNotNull(result);
        assertEquals(50L, result.getEquipmentId());
        assertEquals(1L, result.getWorkOrderId());
        verify(downtimeRecordMapper).insert(any());
        verify(equipmentMapper).updateStatus(50L, "MAINTENANCE");
    }
}
