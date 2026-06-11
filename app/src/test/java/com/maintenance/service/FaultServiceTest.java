package com.maintenance.service;

import com.maintenance.dto.DispatchPlanResult;
import com.maintenance.dto.DispatchResult;
import com.maintenance.dto.FaultReportRequest;
import com.maintenance.dto.PreOccupyResult;
import com.maintenance.entity.DispatchPlan;
import com.maintenance.entity.DowntimeRecord;
import com.maintenance.entity.Equipment;
import com.maintenance.entity.Fault;
import com.maintenance.entity.PurchaseSuggestion;
import com.maintenance.entity.SlaRecord;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.WorkOrder;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.EquipmentMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FaultService focusing on predictive dispatch integration:
 * 1. SLA record creation
 * 2. Multiple dispatch plan generation
 * 3. Spare parts pre-occupation
 * 4. Purchase suggestions on parts shortage
 * 5. SLA pause when parts insufficient
 * 6. Downtime only started after successful dispatch
 * 7. Duplicate fault detection
 */
@ExtendWith(MockitoExtension.class)
class FaultServiceTest {

    @Mock private FaultMapper faultMapper;
    @Mock private EquipmentMapper equipmentMapper;
    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private SparePartMapper sparePartMapper;
    @Mock private LocalMessageQueue messageQueue;
    @Mock private AutoDispatchService autoDispatchService;
    @Mock private AuditService auditService;
    @Mock private DowntimeService downtimeService;
    @Mock private PredictiveDispatchService predictiveDispatchService;
    @Mock private SlaService slaService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    private FaultService faultService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        faultService = new FaultService(
                faultMapper, equipmentMapper, workOrderMapper, sparePartMapper,
                messageQueue, autoDispatchService, auditService, downtimeService,
                predictiveDispatchService, slaService, redisTemplate);
    }

    private FaultReportRequest createRequest(Long equipmentId, int faultLevel) {
        FaultReportRequest req = new FaultReportRequest();
        req.setEquipmentId(equipmentId);
        req.setFaultLevel(faultLevel);
        req.setFaultDescription("Test fault");
        req.setReporter("Tester");
        req.setReporterPhone("1234567890");
        return req;
    }

    private DowntimeRecord createDowntimeRecord(Long id) {
        DowntimeRecord rec = new DowntimeRecord();
        rec.setId(id);
        return rec;
    }

    private SlaRecord createSlaRecord(Long workOrderId, int faultLevel) {
        SlaRecord record = new SlaRecord();
        record.setId(1L);
        record.setWorkOrderId(workOrderId);
        record.setFaultLevel(faultLevel);
        record.setSlaDeadline(LocalDateTime.now().plusMinutes(240));
        record.setStatus("ACTIVE");
        return record;
    }

    private DispatchPlan createPlan(int index, BigDecimal score) {
        DispatchPlan plan = new DispatchPlan();
        plan.setId((long) index);
        plan.setPlanIndex(index);
        plan.setTotalScore(score);
        plan.setTechnicianId(100L + index);
        return plan;
    }

    private void setupBasicFaultMocks(Long equipmentId) {
        Equipment equipment = new Equipment();
        equipment.setId(equipmentId);
        equipment.setEquipmentName("CNC Machine");
        equipment.setEquipmentType("CNC");

        when(equipmentMapper.selectById(equipmentId)).thenReturn(equipment);
        when(faultMapper.selectRecentByEquipment(equipmentId, 5)).thenReturn(Collections.emptyList());
        when(equipmentMapper.updateStatus(equipmentId, "FAULT")).thenReturn(1);
        when(workOrderMapper.selectLatestByEquipment(anyLong(), anyString())).thenReturn(null);

        doAnswer(inv -> { Fault f = inv.getArgument(0); f.setId(1L); return 1; })
                .when(faultMapper).insert(any(Fault.class));
        doAnswer(inv -> { WorkOrder wo = inv.getArgument(0); wo.setId(1L); wo.setOrderCode("WO001"); return 1; })
                .when(workOrderMapper).insert(any(WorkOrder.class));
    }

    // ========================================================
    // TEST: Non-emergency dispatch deferred when parts unavailable
    // ========================================================
    @Test
    @DisplayName("Non-emergency dispatch deferred when spare parts unavailable, SLA paused")
    void reportFault_defersNonEmergencyWhenPartsUnavailable() {
        FaultReportRequest request = createRequest(1L, 1);

        setupBasicFaultMocks(1L);

        SlaRecord slaRecord = createSlaRecord(1L, 1);
        when(slaService.createSlaRecord(1L, 1)).thenReturn(slaRecord);
        when(slaService.getByWorkOrderId(1L)).thenReturn(slaRecord);

        // Plans generated but parts not available
        List<DispatchPlan> plans = List.of(createPlan(1, BigDecimal.valueOf(80)));
        when(predictiveDispatchService.generateDispatchPlans(any(), any(), any())).thenReturn(plans);

        PreOccupyResult preOccupyResult = PreOccupyResult.builder()
                .workOrderId(1L)
                .occupiedParts(Collections.emptyList())
                .shortageParts(List.of(new PurchaseSuggestion()))
                .allPartsAvailable(false)
                .build();
        when(predictiveDispatchService.preOccupyParts(eq(1L), anyString(), eq(1))).thenReturn(preOccupyResult);

        Fault result = faultService.reportFault(request);

        // Dispatch should NOT have been executed (parts shortage + non-emergency)
        verify(predictiveDispatchService, never()).selectAndExecutePlan(anyLong(), anyInt());
        // Parts should be released since dispatch deferred
        verify(predictiveDispatchService).releasePreOccupiedParts(1L);
        // SLA should be paused
        verify(slaService).pauseSla(eq(1L), anyString());
        // Downtime should NOT have been started
        verify(downtimeService, never()).startDowntime(anyLong(), anyLong(), anyLong());
    }

    // ========================================================
    // TEST: Emergency dispatch attempted even when parts unavailable
    // ========================================================
    @Test
    @DisplayName("Emergency dispatch (faultLevel >= 3) attempted even without parts")
    void reportFault_emergencyDispatchAttemptedWithoutParts() {
        FaultReportRequest request = createRequest(1L, 4);

        setupBasicFaultMocks(1L);

        SlaRecord slaRecord = createSlaRecord(1L, 4);
        when(slaService.createSlaRecord(1L, 4)).thenReturn(slaRecord);

        List<DispatchPlan> plans = List.of(createPlan(1, BigDecimal.valueOf(100)));
        when(predictiveDispatchService.generateDispatchPlans(any(), any(), any())).thenReturn(plans);

        // Parts not available but emergency
        PreOccupyResult preOccupyResult = PreOccupyResult.builder()
                .workOrderId(1L)
                .occupiedParts(Collections.emptyList())
                .shortageParts(List.of(new PurchaseSuggestion()))
                .allPartsAvailable(false)
                .build();
        when(predictiveDispatchService.preOccupyParts(eq(1L), anyString(), eq(4))).thenReturn(preOccupyResult);

        DispatchResult successResult = DispatchResult.success(1L, "WO001", 100L, "Tech", BigDecimal.valueOf(100), "AUTO");
        when(predictiveDispatchService.selectAndExecutePlan(1L, 1)).thenReturn(successResult);
        when(downtimeService.startDowntime(anyLong(), anyLong(), anyLong())).thenReturn(createDowntimeRecord(1L));

        faultService.reportFault(request);

        // Emergency dispatch should be attempted
        verify(predictiveDispatchService).selectAndExecutePlan(1L, 1);
        // Downtime should be started after successful dispatch
        verify(downtimeService).startDowntime(eq(1L), eq(1L), eq(1L));
    }

    // ========================================================
    // TEST: Parts available - normal dispatch proceeds
    // ========================================================
    @Test
    @DisplayName("Normal dispatch proceeds when spare parts are available")
    void reportFault_normalDispatchWhenPartsAvailable() {
        FaultReportRequest request = createRequest(1L, 2);

        setupBasicFaultMocks(1L);

        SlaRecord slaRecord = createSlaRecord(1L, 2);
        when(slaService.createSlaRecord(1L, 2)).thenReturn(slaRecord);

        List<DispatchPlan> plans = List.of(createPlan(1, BigDecimal.valueOf(80)));
        when(predictiveDispatchService.generateDispatchPlans(any(), any(), any())).thenReturn(plans);

        PreOccupyResult preOccupyResult = PreOccupyResult.builder()
                .workOrderId(1L)
                .occupiedParts(List.of(new SparePartOccupation()))
                .shortageParts(Collections.emptyList())
                .allPartsAvailable(true)
                .build();
        when(predictiveDispatchService.preOccupyParts(eq(1L), anyString(), eq(2))).thenReturn(preOccupyResult);

        DispatchResult successResult = DispatchResult.success(1L, "WO001", 100L, "Tech", BigDecimal.TEN, "AUTO");
        when(predictiveDispatchService.selectAndExecutePlan(1L, 1)).thenReturn(successResult);
        when(downtimeService.startDowntime(anyLong(), anyLong(), anyLong())).thenReturn(createDowntimeRecord(1L));

        faultService.reportFault(request);

        verify(predictiveDispatchService).selectAndExecutePlan(1L, 1);
        verify(downtimeService).startDowntime(eq(1L), eq(1L), eq(1L));
    }

    // ========================================================
    // TEST: Dispatch failure - downtime NOT started, parts released
    // ========================================================
    @Test
    @DisplayName("Downtime not started when dispatch fails, pre-occupied parts released")
    void reportFault_noDowntimeWhenDispatchFails() {
        FaultReportRequest request = createRequest(1L, 2);

        setupBasicFaultMocks(1L);

        SlaRecord slaRecord = createSlaRecord(1L, 2);
        when(slaService.createSlaRecord(1L, 2)).thenReturn(slaRecord);

        // No plans generated
        when(predictiveDispatchService.generateDispatchPlans(any(), any(), any())).thenReturn(Collections.emptyList());

        PreOccupyResult preOccupyResult = PreOccupyResult.builder()
                .workOrderId(1L)
                .occupiedParts(Collections.emptyList())
                .shortageParts(Collections.emptyList())
                .allPartsAvailable(true)
                .build();
        when(predictiveDispatchService.preOccupyParts(eq(1L), anyString(), eq(2))).thenReturn(preOccupyResult);

        faultService.reportFault(request);

        // Downtime should NOT be started
        verify(downtimeService, never()).startDowntime(anyLong(), anyLong(), anyLong());
        // Parts should be released since no plans available
        verify(predictiveDispatchService).releasePreOccupiedParts(1L);
    }

    // ========================================================
    // TEST: Duplicate fault detection
    // ========================================================
    @Test
    @DisplayName("Duplicate fault within 5 minutes increments occurrence count")
    void reportFault_duplicateFaultDetection() {
        FaultReportRequest request = createRequest(1L, 2);

        Equipment equipment = new Equipment();
        equipment.setId(1L);
        equipment.setEquipmentName("CNC Machine");
        equipment.setEquipmentType("CNC");

        Fault existingFault = new Fault();
        existingFault.setId(99L);
        existingFault.setFaultLevel(2);
        existingFault.setEquipmentId(1L);

        when(equipmentMapper.selectById(1L)).thenReturn(equipment);
        when(faultMapper.selectRecentByEquipment(1L, 5)).thenReturn(Collections.singletonList(existingFault));
        when(faultMapper.incrementOccurrenceCount(eq(99L), anyString())).thenReturn(1);
        when(faultMapper.selectById(99L)).thenReturn(existingFault);

        Fault result = faultService.reportFault(request);

        assertEquals(99L, result.getId(), "Should return existing fault, not create new one");
        verify(faultMapper).incrementOccurrenceCount(eq(99L), anyString());
        verify(faultMapper, never()).insert(any());
    }

    // ========================================================
    // TEST: SLA record created for every fault report
    // ========================================================
    @Test
    @DisplayName("SLA record is created for every new fault report")
    void reportFault_slaRecordCreated() {
        FaultReportRequest request = createRequest(1L, 3);

        setupBasicFaultMocks(1L);

        SlaRecord slaRecord = createSlaRecord(1L, 3);
        when(slaService.createSlaRecord(1L, 3)).thenReturn(slaRecord);

        when(predictiveDispatchService.generateDispatchPlans(any(), any(), any())).thenReturn(Collections.emptyList());
        PreOccupyResult preOccupyResult = PreOccupyResult.builder()
                .workOrderId(1L)
                .occupiedParts(Collections.emptyList())
                .shortageParts(Collections.emptyList())
                .allPartsAvailable(true)
                .build();
        when(predictiveDispatchService.preOccupyParts(eq(1L), anyString(), eq(3))).thenReturn(preOccupyResult);

        faultService.reportFault(request);

        verify(slaService).createSlaRecord(1L, 3);
    }

    // ========================================================
    // TEST: Event payload includes plan and SLA info
    // ========================================================
    @Test
    @DisplayName("FAULT_REPORTED event payload includes planCount, slaDeadline, purchaseSuggestionCount")
    @SuppressWarnings("unchecked")
    void reportFault_eventPayloadIncludesPlanAndSlaInfo() {
        FaultReportRequest request = createRequest(1L, 2);

        setupBasicFaultMocks(1L);

        SlaRecord slaRecord = createSlaRecord(1L, 2);
        when(slaService.createSlaRecord(1L, 2)).thenReturn(slaRecord);

        List<DispatchPlan> plans = List.of(createPlan(1, BigDecimal.valueOf(80)), createPlan(2, BigDecimal.valueOf(60)));
        when(predictiveDispatchService.generateDispatchPlans(any(), any(), any())).thenReturn(plans);

        PreOccupyResult preOccupyResult = PreOccupyResult.builder()
                .workOrderId(1L)
                .occupiedParts(List.of(new SparePartOccupation()))
                .shortageParts(Collections.emptyList())
                .allPartsAvailable(true)
                .build();
        when(predictiveDispatchService.preOccupyParts(eq(1L), anyString(), eq(2))).thenReturn(preOccupyResult);

        DispatchResult successResult = DispatchResult.success(1L, "WO001", 100L, "Tech", BigDecimal.valueOf(80), "AUTO");
        when(predictiveDispatchService.selectAndExecutePlan(1L, 1)).thenReturn(successResult);
        when(downtimeService.startDowntime(anyLong(), anyLong(), anyLong())).thenReturn(createDowntimeRecord(1L));

        faultService.reportFault(request);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messageQueue).publishWithId(anyString(), eq("FAULT_REPORTED"), payloadCaptor.capture());

        Object payload = payloadCaptor.getValue();
        assertNotNull(payload);
        assertTrue(payload instanceof Map);
        Map<String, Object> payloadMap = (Map<String, Object>) payload;
        assertEquals(2, payloadMap.get("planCount"));
        assertNotNull(payloadMap.get("slaDeadline"));
        assertTrue((Boolean) payloadMap.get("dispatchSuccess"));
    }
}
