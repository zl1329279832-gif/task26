package com.maintenance.service;

import com.maintenance.entity.SlaRecord;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.SlaRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SlaService covering:
 * 1. SLA creation with correct deadlines by fault level
 * 2. SLA pause saves remaining time
 * 3. SLA resume recalculates deadline
 * 4. SLA finalize marks as MET or EXPIRED
 * 5. Duplicate SLA creation returns existing
 * 6. SLA score calculation
 */
@ExtendWith(MockitoExtension.class)
class SlaServiceTest {

    @Mock private SlaRecordMapper slaRecordMapper;
    @Mock private LocalMessageQueue messageQueue;
    @Mock private AuditService auditService;

    private SlaService slaService;

    @BeforeEach
    void setUp() {
        slaService = new SlaService(slaRecordMapper, messageQueue, auditService);
    }

    // ========================================================
    // TEST: SLA creation with correct deadlines
    // ========================================================
    @Test
    @DisplayName("SLA record created with correct deadline based on fault level")
    void createSlaRecord_correctDeadlines() {
        when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(null);
        when(slaRecordMapper.insert(any(SlaRecord.class))).thenReturn(1);

        SlaRecord record = slaService.createSlaRecord(1L, 4);

        assertNotNull(record);
        assertEquals("ACTIVE", record.getStatus());
        assertEquals(4, record.getFaultLevel());
        assertEquals(1L, record.getWorkOrderId());
        // Fault level 4 = 60 minutes
        assertNotNull(record.getSlaDeadline());
        assertTrue(record.getSlaDeadline().isAfter(LocalDateTime.now().plusMinutes(55)));
        assertTrue(record.getSlaDeadline().isBefore(LocalDateTime.now().plusMinutes(65)));
    }

    @Test
    @DisplayName("SLA record for fault level 1 has 480-minute deadline")
    void createSlaRecord_level1_480Minutes() {
        when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(null);
        when(slaRecordMapper.insert(any(SlaRecord.class))).thenReturn(1);

        SlaRecord record = slaService.createSlaRecord(1L, 1);

        assertTrue(record.getSlaDeadline().isAfter(LocalDateTime.now().plusMinutes(475)));
        assertTrue(record.getSlaDeadline().isBefore(LocalDateTime.now().plusMinutes(485)));
    }

    // ========================================================
    // TEST: Duplicate SLA creation returns existing
    // ========================================================
    @Test
    @DisplayName("Duplicate SLA creation returns existing record")
    void createSlaRecord_duplicateReturnsExisting() {
        SlaRecord existing = new SlaRecord();
        existing.setId(1L);
        existing.setWorkOrderId(1L);
        existing.setStatus("ACTIVE");

        when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(existing);

        SlaRecord result = slaService.createSlaRecord(1L, 2);

        assertEquals(existing, result);
        verify(slaRecordMapper, never()).insert(any());
    }

    // ========================================================
    // TEST: SLA pause saves remaining time
    // ========================================================
    @Test
    @DisplayName("SLA pause saves remaining minutes and marks as PAUSED")
    void pauseSla_savesRemainingTime() {
        SlaRecord activeRecord = new SlaRecord();
        activeRecord.setId(1L);
        activeRecord.setWorkOrderId(1L);
        activeRecord.setStatus("ACTIVE");
        activeRecord.setSlaDeadline(LocalDateTime.now().plusMinutes(100));

        when(slaRecordMapper.selectActiveByWorkOrder(1L)).thenReturn(activeRecord);
        when(slaRecordMapper.updateSla(eq(1L), eq("PAUSED"), anyInt(), any(), isNull(), any())).thenReturn(1);

        SlaRecord result = slaService.pauseSla(1L, "waiting for parts");

        assertEquals("PAUSED", result.getStatus());
        assertNotNull(result.getPauseReason());
        assertTrue(result.getPauseReason().contains("waiting for parts"));
        verify(messageQueue).publishWithId(anyString(), eq("SLA_PAUSED"), any());
    }

    // ========================================================
    // TEST: SLA pause returns null when no active record
    // ========================================================
    @Test
    @DisplayName("SLA pause returns null when no active SLA record")
    void pauseSla_noActiveRecord() {
        when(slaRecordMapper.selectActiveByWorkOrder(99L)).thenReturn(null);

        SlaRecord result = slaService.pauseSla(99L, "no record");

        assertNull(result);
    }

    // ========================================================
    // TEST: SLA resume recalculates deadline
    // ========================================================
    @Test
    @DisplayName("SLA resume recalculates deadline from remaining minutes")
    void resumeSla_recalculatesDeadline() {
        SlaRecord pausedRecord = new SlaRecord();
        pausedRecord.setId(1L);
        pausedRecord.setWorkOrderId(1L);
        pausedRecord.setStatus("PAUSED");
        pausedRecord.setRemainingMinutes(60);
        pausedRecord.setPausedAt(LocalDateTime.now().minusMinutes(30));
        pausedRecord.setSlaDeadline(LocalDateTime.now().minusMinutes(30));

        when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(pausedRecord);
        when(slaRecordMapper.updateSla(eq(1L), eq("ACTIVE"), eq(60), any(), any(), any())).thenReturn(1);

        SlaRecord result = slaService.resumeSla(1L);

        assertEquals("ACTIVE", result.getStatus());
        assertNotNull(result.getSlaDeadline());
        // New deadline should be ~60 minutes from now
        assertTrue(result.getSlaDeadline().isAfter(LocalDateTime.now().plusMinutes(55)));
        assertTrue(result.getSlaDeadline().isBefore(LocalDateTime.now().plusMinutes(65)));
        verify(messageQueue).publishWithId(anyString(), eq("SLA_RESUMED"), any());
    }

    // ========================================================
    // TEST: SLA finalize marks MET when before deadline
    // ========================================================
    @Test
    @DisplayName("SLA finalize marks MET when completed before deadline")
    void finalizeSla_marksMetBeforeDeadline() {
        SlaRecord record = new SlaRecord();
        record.setId(1L);
        record.setWorkOrderId(1L);
        record.setStatus("ACTIVE");
        record.setSlaDeadline(LocalDateTime.now().plusHours(2)); // Still have time

        when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(record);
        when(slaRecordMapper.updateStatus(1L, "MET")).thenReturn(1);

        SlaRecord result = slaService.finalizeSla(1L);

        assertEquals("MET", result.getStatus());
    }

    // ========================================================
    // TEST: SLA finalize marks EXPIRED when past deadline
    // ========================================================
    @Test
    @DisplayName("SLA finalize marks EXPIRED when past deadline")
    void finalizeSla_marksExpiredAfterDeadline() {
        SlaRecord record = new SlaRecord();
        record.setId(1L);
        record.setWorkOrderId(1L);
        record.setStatus("ACTIVE");
        record.setSlaDeadline(LocalDateTime.now().minusHours(1)); // Past deadline

        when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(record);
        when(slaRecordMapper.updateStatus(1L, "EXPIRED")).thenReturn(1);

        SlaRecord result = slaService.finalizeSla(1L);

        assertEquals("EXPIRED", result.getStatus());
    }

    // ========================================================
    // TEST: SLA finalize with PAUSED status and remaining time
    // ========================================================
    @Test
    @DisplayName("SLA finalize with PAUSED status and remaining > 0 marks MET")
    void finalizeSla_pausedWithRemainingTime() {
        SlaRecord record = new SlaRecord();
        record.setId(1L);
        record.setWorkOrderId(1L);
        record.setStatus("PAUSED");
        record.setRemainingMinutes(30);

        when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(record);
        when(slaRecordMapper.updateSla(eq(1L), eq("MET"), eq(30), any(), any(), any())).thenReturn(1);

        SlaRecord result = slaService.finalizeSla(1L);

        assertEquals("MET", result.getStatus());
    }

    // ========================================================
    // TEST: SLA score calculation
    // ========================================================
    @Test
    @DisplayName("SLA score is higher when less time remaining")
    void calculateSlaScore_higherWithLessTime() {
        // Active SLA with very little time remaining
        SlaRecord urgentRecord = new SlaRecord();
        urgentRecord.setId(1L);
        urgentRecord.setWorkOrderId(1L);
        urgentRecord.setStatus("ACTIVE");
        urgentRecord.setFaultLevel(4); // 60 min total
        urgentRecord.setSlaDeadline(LocalDateTime.now().plusMinutes(3)); // 5% remaining

        when(slaRecordMapper.selectActiveByWorkOrder(1L)).thenReturn(urgentRecord);

        int score = slaService.calculateSlaScore(1L);

        // With only 5% remaining, score should be high (>= 12)
        assertTrue(score >= 12, "SLA score should be high with <10% remaining, got " + score);
    }

    @Test
    @DisplayName("SLA score is 0 when no active SLA record")
    void calculateSlaScore_zeroWhenNoRecord() {
        when(slaRecordMapper.selectActiveByWorkOrder(99L)).thenReturn(null);

        int score = slaService.calculateSlaScore(99L);

        assertEquals(0, score);
    }

    // ========================================================
    // TEST: getSlaMinutes returns correct values
    // ========================================================
    @Test
    @DisplayName("getSlaMinutes returns correct values for each fault level")
    void getSlaMinutes() {
        assertEquals(480, slaService.getSlaMinutes(1));
        assertEquals(240, slaService.getSlaMinutes(2));
        assertEquals(120, slaService.getSlaMinutes(3));
        assertEquals(60, slaService.getSlaMinutes(4));
        // Invalid level defaults to L1
        assertEquals(480, slaService.getSlaMinutes(0));
        assertEquals(480, slaService.getSlaMinutes(5));
    }

    // ========================================================
    // TEST: Resume non-paused SLA returns as-is
    // ========================================================
    @Test
    @DisplayName("Resume non-paused SLA returns the record unchanged")
    void resumeSla_nonPausedReturnsUnchanged() {
        SlaRecord activeRecord = new SlaRecord();
        activeRecord.setId(1L);
        activeRecord.setWorkOrderId(1L);
        activeRecord.setStatus("ACTIVE");

        when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(activeRecord);

        SlaRecord result = slaService.resumeSla(1L);

        assertEquals("ACTIVE", result.getStatus());
        verify(slaRecordMapper, never()).updateSla(anyLong(), anyString(), anyInt(), any(), any(), any());
    }
}
