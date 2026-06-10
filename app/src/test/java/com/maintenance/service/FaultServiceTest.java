package com.maintenance.service;

import com.maintenance.dto.DispatchResult;
import com.maintenance.dto.FaultReportRequest;
import com.maintenance.entity.DowntimeRecord;
import com.maintenance.entity.Equipment;
import com.maintenance.entity.Fault;
import com.maintenance.entity.SparePart;
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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FaultService focusing on:
 * 1. Spare part availability check before dispatch
 * 2. Dispatch deferred when parts unavailable (non-emergency)
 * 3. Emergency dispatch attempted regardless of parts
 * 4. Downtime only started after successful dispatch
 * 5. Duplicate fault detection
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

    private FaultService faultService;

    @BeforeEach
    void setUp() {
        faultService = new FaultService(
                faultMapper, equipmentMapper, workOrderMapper, sparePartMapper,
                messageQueue, autoDispatchService, auditService, downtimeService);
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

    // ========================================================
    // TEST: Parts unavailable - non-emergency dispatch deferred
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Non-emergency dispatch deferred when spare parts unavailable")
    void reportFault_defersNonEmergencyWhenPartsUnavailable() {
        FaultReportRequest request = createRequest(1L, 1); // faultLevel=1 (MINOR)

        Equipment equipment = new Equipment();
        equipment.setId(1L);
        equipment.setEquipmentName("CNC Machine");
        equipment.setEquipmentType("CNC");

        // All applicable parts out of stock
        SparePart emptyPart = new SparePart();
        emptyPart.setStockQuantity(0);
        emptyPart.setPartCode("P001");

        when(equipmentMapper.selectById(1L)).thenReturn(equipment);
        when(faultMapper.selectRecentByEquipment(1L, 5)).thenReturn(Collections.emptyList());
        when(equipmentMapper.updateStatus(1L, "FAULT")).thenReturn(1);
        when(workOrderMapper.selectLatestByEquipment(anyLong(), anyString())).thenReturn(null);
        when(sparePartMapper.selectByEquipmentType("CNC")).thenReturn(Collections.singletonList(emptyPart));

        doAnswer(inv -> { Fault f = inv.getArgument(0); f.setId(1L); return 1; })
                .when(faultMapper).insert(any(Fault.class));
        doAnswer(inv -> { WorkOrder wo = inv.getArgument(0); wo.setId(1L); return 1; })
                .when(workOrderMapper).insert(any(WorkOrder.class));

        Fault result = faultService.reportFault(request);

        // Dispatch should NOT have been attempted
        verify(autoDispatchService, never()).autoDispatch(any(), any());
        verify(autoDispatchService, never()).emergencyDispatch(any(), any());
        // Downtime should NOT have been started
        verify(downtimeService, never()).startDowntime(anyLong(), anyLong(), anyLong());
    }

    // ========================================================
    // TEST: Emergency dispatch attempted even when parts unavailable
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Emergency dispatch (faultLevel >= 3) attempted even without parts")
    void reportFault_emergencyDispatchAttemptedWithoutParts() {
        FaultReportRequest request = createRequest(1L, 4); // faultLevel=4 (CRITICAL)

        Equipment equipment = new Equipment();
        equipment.setId(1L);
        equipment.setEquipmentName("CNC Machine");
        equipment.setEquipmentType("CNC");

        SparePart emptyPart = new SparePart();
        emptyPart.setStockQuantity(0);

        when(equipmentMapper.selectById(1L)).thenReturn(equipment);
        when(faultMapper.selectRecentByEquipment(1L, 5)).thenReturn(Collections.emptyList());
        when(equipmentMapper.updateStatus(1L, "FAULT")).thenReturn(1);
        when(workOrderMapper.selectLatestByEquipment(anyLong(), anyString())).thenReturn(null);
        when(sparePartMapper.selectByEquipmentType("CNC")).thenReturn(Collections.singletonList(emptyPart));

        doAnswer(inv -> { Fault f = inv.getArgument(0); f.setId(1L); return 1; })
                .when(faultMapper).insert(any(Fault.class));
        doAnswer(inv -> { WorkOrder wo = inv.getArgument(0); wo.setId(1L); return 1; })
                .when(workOrderMapper).insert(any(WorkOrder.class));

        // Emergency dispatch succeeds
        DispatchResult successResult = DispatchResult.success(1L, "WO001", 100L, "Tech", BigDecimal.TEN, "AUTO");
        when(autoDispatchService.emergencyDispatch(any(), any())).thenReturn(successResult);
        when(downtimeService.startDowntime(anyLong(), anyLong(), anyLong())).thenReturn(createDowntimeRecord(1L));

        faultService.reportFault(request);

        // Emergency dispatch should be attempted
        verify(autoDispatchService).emergencyDispatch(any(), any());
        // Downtime should be started after successful dispatch
        verify(downtimeService).startDowntime(eq(1L), eq(1L), eq(1L));
    }

    // ========================================================
    // TEST: Parts available - normal dispatch proceeds
    // ========================================================
    @Test
    @DisplayName("Normal dispatch proceeds when spare parts are available")
    void reportFault_normalDispatchWhenPartsAvailable() {
        FaultReportRequest request = createRequest(1L, 2); // faultLevel=2 (MODERATE)

        Equipment equipment = new Equipment();
        equipment.setId(1L);
        equipment.setEquipmentName("CNC Machine");
        equipment.setEquipmentType("CNC");

        SparePart partInStock = new SparePart();
        partInStock.setStockQuantity(10);

        when(equipmentMapper.selectById(1L)).thenReturn(equipment);
        when(faultMapper.selectRecentByEquipment(1L, 5)).thenReturn(Collections.emptyList());
        when(equipmentMapper.updateStatus(1L, "FAULT")).thenReturn(1);
        when(workOrderMapper.selectLatestByEquipment(anyLong(), anyString())).thenReturn(null);
        when(sparePartMapper.selectByEquipmentType("CNC")).thenReturn(Collections.singletonList(partInStock));

        doAnswer(inv -> { Fault f = inv.getArgument(0); f.setId(1L); return 1; })
                .when(faultMapper).insert(any(Fault.class));
        doAnswer(inv -> { WorkOrder wo = inv.getArgument(0); wo.setId(1L); return 1; })
                .when(workOrderMapper).insert(any(WorkOrder.class));

        DispatchResult successResult = DispatchResult.success(1L, "WO001", 100L, "Tech", BigDecimal.TEN, "AUTO");
        when(autoDispatchService.autoDispatch(any(), any())).thenReturn(successResult);
        when(downtimeService.startDowntime(anyLong(), anyLong(), anyLong())).thenReturn(createDowntimeRecord(1L));

        faultService.reportFault(request);

        verify(autoDispatchService).autoDispatch(any(), any());
        verify(downtimeService).startDowntime(eq(1L), eq(1L), eq(1L));
    }

    // ========================================================
    // TEST: Dispatch failure - downtime NOT started
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Downtime not started when dispatch fails")
    void reportFault_noDowntimeWhenDispatchFails() {
        FaultReportRequest request = createRequest(1L, 2);

        Equipment equipment = new Equipment();
        equipment.setId(1L);
        equipment.setEquipmentName("CNC Machine");
        equipment.setEquipmentType("CNC");

        SparePart partInStock = new SparePart();
        partInStock.setStockQuantity(10);

        when(equipmentMapper.selectById(1L)).thenReturn(equipment);
        when(faultMapper.selectRecentByEquipment(1L, 5)).thenReturn(Collections.emptyList());
        when(equipmentMapper.updateStatus(1L, "FAULT")).thenReturn(1);
        when(workOrderMapper.selectLatestByEquipment(anyLong(), anyString())).thenReturn(null);
        when(sparePartMapper.selectByEquipmentType("CNC")).thenReturn(Collections.singletonList(partInStock));

        doAnswer(inv -> { Fault f = inv.getArgument(0); f.setId(1L); return 1; })
                .when(faultMapper).insert(any(Fault.class));
        doAnswer(inv -> { WorkOrder wo = inv.getArgument(0); wo.setId(1L); return 1; })
                .when(workOrderMapper).insert(any(WorkOrder.class));

        // Dispatch fails
        DispatchResult failResult = DispatchResult.fail("No qualified technician");
        when(autoDispatchService.autoDispatch(any(), any())).thenReturn(failResult);

        faultService.reportFault(request);

        // Downtime should NOT be started
        verify(downtimeService, never()).startDowntime(anyLong(), anyLong(), anyLong());
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

        // Existing fault with same level within 5 minutes
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
    // TEST: No parts configured for equipment type - dispatch proceeds
    // ========================================================
    @Test
    @DisplayName("No parts configured for equipment type: dispatch proceeds (no parts needed)")
    void reportFault_noPartsConfigured_dispatchProceeds() {
        FaultReportRequest request = createRequest(1L, 1);

        Equipment equipment = new Equipment();
        equipment.setId(1L);
        equipment.setEquipmentName("Generic Machine");
        equipment.setEquipmentType("GENERIC");

        when(equipmentMapper.selectById(1L)).thenReturn(equipment);
        when(faultMapper.selectRecentByEquipment(1L, 5)).thenReturn(Collections.emptyList());
        when(equipmentMapper.updateStatus(1L, "FAULT")).thenReturn(1);
        when(workOrderMapper.selectLatestByEquipment(anyLong(), anyString())).thenReturn(null);
        // No parts configured for this equipment type
        when(sparePartMapper.selectByEquipmentType("GENERIC")).thenReturn(Collections.emptyList());

        doAnswer(inv -> { Fault f = inv.getArgument(0); f.setId(1L); return 1; })
                .when(faultMapper).insert(any(Fault.class));
        doAnswer(inv -> { WorkOrder wo = inv.getArgument(0); wo.setId(1L); return 1; })
                .when(workOrderMapper).insert(any(WorkOrder.class));

        DispatchResult successResult = DispatchResult.success(1L, "WO001", 100L, "Tech", BigDecimal.TEN, "AUTO");
        when(autoDispatchService.autoDispatch(any(), any())).thenReturn(successResult);
        when(downtimeService.startDowntime(anyLong(), anyLong(), anyLong())).thenReturn(createDowntimeRecord(1L));

        faultService.reportFault(request);

        // Dispatch should proceed (no parts needed for this type)
        verify(autoDispatchService).autoDispatch(any(), any());
    }

    // ========================================================
    // TEST: Event payload includes parts availability info
    // ========================================================
    @Test
    @DisplayName("FAULT_REPORTED event payload includes partsAvailable and downtimeStarted flags")
    @SuppressWarnings("unchecked")
    void reportFault_eventPayloadIncludesPartsAndDowntimeInfo() {
        FaultReportRequest request = createRequest(1L, 2);

        Equipment equipment = new Equipment();
        equipment.setId(1L);
        equipment.setEquipmentName("CNC Machine");
        equipment.setEquipmentType("CNC");

        SparePart partInStock = new SparePart();
        partInStock.setStockQuantity(5);

        when(equipmentMapper.selectById(1L)).thenReturn(equipment);
        when(faultMapper.selectRecentByEquipment(1L, 5)).thenReturn(Collections.emptyList());
        when(equipmentMapper.updateStatus(1L, "FAULT")).thenReturn(1);
        when(workOrderMapper.selectLatestByEquipment(anyLong(), anyString())).thenReturn(null);
        when(sparePartMapper.selectByEquipmentType("CNC")).thenReturn(Collections.singletonList(partInStock));

        doAnswer(inv -> { Fault f = inv.getArgument(0); f.setId(1L); return 1; })
                .when(faultMapper).insert(any(Fault.class));
        doAnswer(inv -> { WorkOrder wo = inv.getArgument(0); wo.setId(1L); return 1; })
                .when(workOrderMapper).insert(any(WorkOrder.class));

        DispatchResult successResult = DispatchResult.success(1L, "WO001", 100L, "Tech", BigDecimal.TEN, "AUTO");
        when(autoDispatchService.autoDispatch(any(), any())).thenReturn(successResult);
        when(downtimeService.startDowntime(anyLong(), anyLong(), anyLong())).thenReturn(createDowntimeRecord(1L));

        faultService.reportFault(request);

        // Capture the event payload
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messageQueue).publish(eq("FAULT_REPORTED"), payloadCaptor.capture());

        Object payload = payloadCaptor.getValue();
        assertNotNull(payload);
        assertTrue(payload instanceof Map);
        Map<String, Object> payloadMap = (Map<String, Object>) payload;
        assertTrue((Boolean) payloadMap.get("partsAvailable"), "partsAvailable should be true");
        assertTrue((Boolean) payloadMap.get("downtimeStarted"), "downtimeStarted should be true");
        assertTrue((Boolean) payloadMap.get("dispatchSuccess"), "dispatchSuccess should be true");
    }
}
