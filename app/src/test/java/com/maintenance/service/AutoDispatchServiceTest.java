package com.maintenance.service;

import com.maintenance.dto.DispatchResult;
import com.maintenance.entity.Fault;
import com.maintenance.entity.Technician;
import com.maintenance.entity.TechnicianSkill;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.DispatchRecordMapper;
import com.maintenance.mapper.TechnicianMapper;
import com.maintenance.mapper.TechnicianSkillMapper;
import com.maintenance.mapper.WorkOrderMapper;
import com.maintenance.websocket.MaintenanceWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for AutoDispatchService focusing on:
 * 1. OFFLINE/ON_LEAVE technicians are excluded from dispatch
 * 2. Technicians without matching skills are excluded
 * 3. Emergency dispatch preempts low-priority orders correctly
 * 4. Technician offline during dispatch falls back to next candidate
 */
@ExtendWith(MockitoExtension.class)
class AutoDispatchServiceTest {

    @Mock private TechnicianMapper technicianMapper;
    @Mock private TechnicianSkillMapper technicianSkillMapper;
    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private DispatchRecordMapper dispatchRecordMapper;
    @Mock private LocalMessageQueue messageQueue;
    @Mock private AuditService auditService;
    @Mock private TechnicianService technicianService;
    @Mock private MaintenanceWebSocketHandler webSocketHandler;

    private AutoDispatchService autoDispatchService;

    @BeforeEach
    void setUp() {
        autoDispatchService = new AutoDispatchService(
                technicianMapper, technicianSkillMapper, workOrderMapper,
                dispatchRecordMapper, messageQueue, auditService,
                technicianService, webSocketHandler);
    }

    // --- Helper methods ---

    private Technician createTechnician(Long id, String name, String availability, int workload) {
        Technician tech = new Technician();
        tech.setId(id);
        tech.setName(name);
        tech.setAvailability(availability);
        tech.setCurrentWorkload(workload);
        tech.setSkillLevel(3);
        return tech;
    }

    private TechnicianSkill createSkill(Long techId, String equipmentType, int proficiency, int certLevel) {
        TechnicianSkill skill = new TechnicianSkill();
        skill.setTechnicianId(techId);
        skill.setEquipmentType(equipmentType);
        skill.setProficiency(proficiency);
        skill.setCertifiedFaultLevel(certLevel);
        return skill;
    }

    private Fault createFault(Long id, String equipmentType, int faultLevel) {
        Fault fault = new Fault();
        fault.setId(id);
        fault.setEquipmentType(equipmentType);
        fault.setFaultLevel(faultLevel);
        return fault;
    }

    private WorkOrder createWorkOrder(Long id, String orderCode, int priority) {
        WorkOrder wo = new WorkOrder();
        wo.setId(id);
        wo.setOrderCode(orderCode);
        wo.setPriority(priority);
        wo.setStatus("CREATED");
        return wo;
    }

    // ========================================================
    // TEST: OFFLINE technician should be excluded from dispatch
    // ========================================================
    @Test
    @DisplayName("BUG FIX: OFFLINE technician must NOT be selected even with highest score")
    void autoDispatch_excludesOfflineTechnicians() {
        // Scenario: One OFFLINE technician with perfect skills and one AVAILABLE with lower skills
        Technician offlineTech = createTechnician(1L, "ExpertOffline", TechnicianAvailability.OFFLINE.name(), 0);
        Technician availableTech = createTechnician(2L, "BasicAvailable", TechnicianAvailability.AVAILABLE.name(), 0);

        Fault fault = createFault(1L, "CNC", 2);
        WorkOrder workOrder = createWorkOrder(1L, "WO001", 2);

        when(technicianMapper.selectList(null)).thenReturn(Arrays.asList(offlineTech, availableTech));

        // OFFLINE tech is filtered out before skill lookup, so no stub needed for tech 1L
        // Only the AVAILABLE tech's skill is looked up
        when(technicianSkillMapper.selectByTechnicianAndType(2L, "CNC"))
                .thenReturn(createSkill(2L, "CNC", 2, 2));

        when(workOrderMapper.countByTechnicianAndStatus(anyLong(), eq("COMPLETED"))).thenReturn(10);
        when(webSocketHandler.isOnline(2L)).thenReturn(true);
        when(dispatchRecordMapper.insert(any())).thenReturn(1);
        when(workOrderMapper.updateById(any())).thenReturn(1);

        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        assertTrue(result.isSuccess());
        // The AVAILABLE tech (id=2) should be selected, NOT the OFFLINE one (id=1)
        assertEquals(2L, result.getTechnicianId(), "OFFLINE technician should NOT be selected");
        verify(technicianService).incrementWorkload(2L);
    }

    // ========================================================
    // TEST: ON_LEAVE technician should be excluded from dispatch
    // ========================================================
    @Test
    @DisplayName("BUG FIX: ON_LEAVE technician must NOT be selected")
    void autoDispatch_excludesOnLeaveTechnicians() {
        Technician onLeaveTech = createTechnician(1L, "OnLeave", TechnicianAvailability.ON_LEAVE.name(), 0);
        Fault fault = createFault(1L, "CNC", 1);
        WorkOrder workOrder = createWorkOrder(1L, "WO002", 1);

        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(onLeaveTech));
        // ON_LEAVE tech is filtered out before skill lookup, no stub needed

        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        assertFalse(result.isSuccess(), "Should fail when only ON_LEAVE tech available");
        assertTrue(result.getMessage().contains("No qualified technician"));
    }

    // ========================================================
    // TEST: Technician without matching skill must be excluded
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Technician without matching equipment skill must NOT be selected")
    void autoDispatch_excludesTechniciansWithoutSkill() {
        Technician techNoSkill = createTechnician(1L, "NoSkill", TechnicianAvailability.AVAILABLE.name(), 0);
        Fault fault = createFault(1L, "CNC", 1);
        WorkOrder workOrder = createWorkOrder(1L, "WO003", 1);

        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(techNoSkill));
        // No skill for this technician+equipment combination
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC")).thenReturn(null);

        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        assertFalse(result.isSuccess(), "Should fail when tech has no matching skill");
    }

    // ========================================================
    // TEST: Technician with insufficient certification is excluded
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Technician with cert level too far below fault level must be excluded")
    void autoDispatch_excludesUndercertifiedTechnicians() {
        // Technician certLevel=1, faultLevel=4 -> deficit=3 > 1 -> excluded
        Technician tech = createTechnician(1L, "UnderCert", TechnicianAvailability.AVAILABLE.name(), 0);
        Fault fault = createFault(1L, "CNC", 4);
        WorkOrder workOrder = createWorkOrder(1L, "WO004", 4);

        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(tech));
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC"))
                .thenReturn(createSkill(1L, "CNC", 3, 1)); // cert=1, fault=4, deficit=3

        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        assertFalse(result.isSuccess(), "Should fail when certification deficit > 1");
    }

    // ========================================================
    // TEST: Successful dispatch with qualified AVAILABLE tech
    // ========================================================
    @Test
    @DisplayName("Happy path: AVAILABLE technician with matching skills is dispatched")
    void autoDispatch_selectsQualifiedAvailableTechnician() {
        Technician tech = createTechnician(1L, "GoodTech", TechnicianAvailability.AVAILABLE.name(), 0);
        Fault fault = createFault(1L, "CNC", 2);
        WorkOrder workOrder = createWorkOrder(1L, "WO005", 2);

        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(tech));
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC"))
                .thenReturn(createSkill(1L, "CNC", 4, 3));
        when(workOrderMapper.countByTechnicianAndStatus(1L, "COMPLETED")).thenReturn(15);
        when(webSocketHandler.isOnline(1L)).thenReturn(true);
        when(dispatchRecordMapper.insert(any())).thenReturn(1);
        when(workOrderMapper.updateById(any())).thenReturn(1);

        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        assertTrue(result.isSuccess());
        assertEquals(1L, result.getTechnicianId());
        verify(technicianService).incrementWorkload(1L);
        verify(messageQueue).publish(eq("DISPATCH_DONE"), any());
    }

    // ========================================================
    // TEST: Emergency dispatch - preempt low priority order
    // ========================================================
    @Test
    @DisplayName("Emergency dispatch: preempts low-priority order when no tech available")
    void emergencyDispatch_preemptsLowPriorityOrder() {
        // Setup: one tech who is BUSY with a low-priority order
        Technician busyTech = createTechnician(1L, "BusyTech", TechnicianAvailability.BUSY.name(), 1);

        Fault emergencyFault = createFault(1L, "CNC", 4);
        WorkOrder emergencyOrder = createWorkOrder(2L, "WO-EMERGENCY", 4);

        // Low-priority active order
        WorkOrder lowPriorityOrder = createWorkOrder(1L, "WO-LOW", 1);
        lowPriorityOrder.setTechnicianId(1L);
        lowPriorityOrder.setStatus("REPAIRING");

        // First: normal dispatch will fail (no AVAILABLE tech with matching skills)
        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(busyTech));
        // No skill for normal dispatch pass (to make it fail)
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC")).thenReturn(null);

        // For emergency candidate search
        when(workOrderMapper.selectActiveByTechnicianId(1L))
                .thenReturn(Collections.singletonList(lowPriorityOrder));
        when(dispatchRecordMapper.insert(any())).thenReturn(1);
        when(workOrderMapper.updateById(any())).thenReturn(1);

        DispatchResult result = autoDispatchService.emergencyDispatch(emergencyOrder, emergencyFault);

        assertTrue(result.isSuccess(), "Emergency dispatch should succeed via preemption");
        assertEquals(1L, result.getTechnicianId());
        // Verify the low-priority order was suspended
        assertEquals("SUSPENDED", lowPriorityOrder.getStatus());
    }

    // ========================================================
    // TEST: All technicians OFFLINE - dispatch should fail
    // ========================================================
    @Test
    @DisplayName("All technicians OFFLINE: dispatch must fail with clear message")
    void autoDispatch_failsWhenAllOffline() {
        Technician tech1 = createTechnician(1L, "Off1", TechnicianAvailability.OFFLINE.name(), 0);
        Technician tech2 = createTechnician(2L, "Off2", TechnicianAvailability.OFFLINE.name(), 0);

        Fault fault = createFault(1L, "CNC", 1);
        WorkOrder workOrder = createWorkOrder(1L, "WO006", 1);

        when(technicianMapper.selectList(null)).thenReturn(Arrays.asList(tech1, tech2));

        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        assertFalse(result.isSuccess());
    }

    // ========================================================
    // TEST: No technicians at all
    // ========================================================
    @Test
    @DisplayName("No technicians in system: dispatch must fail")
    void autoDispatch_failsWhenNoTechnicians() {
        Fault fault = createFault(1L, "CNC", 1);
        WorkOrder workOrder = createWorkOrder(1L, "WO007", 1);

        when(technicianMapper.selectList(null)).thenReturn(Collections.emptyList());

        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        assertFalse(result.isSuccess());
        assertEquals("No technicians available", result.getMessage());
    }

    // ========================================================
    // TEST: BUSY technician CAN be dispatched (with lower score)
    // ========================================================
    @Test
    @DisplayName("BUSY technician can still be dispatched (score penalty only)")
    void autoDispatch_allowsBusyTechnician() {
        Technician busyTech = createTechnician(1L, "BusyTech", TechnicianAvailability.BUSY.name(), 2);
        Fault fault = createFault(1L, "CNC", 1);
        WorkOrder workOrder = createWorkOrder(1L, "WO008", 1);

        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(busyTech));
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC"))
                .thenReturn(createSkill(1L, "CNC", 5, 4));
        when(workOrderMapper.countByTechnicianAndStatus(1L, "COMPLETED")).thenReturn(20);
        when(webSocketHandler.isOnline(1L)).thenReturn(true);
        when(dispatchRecordMapper.insert(any())).thenReturn(1);
        when(workOrderMapper.updateById(any())).thenReturn(1);

        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        assertTrue(result.isSuccess(), "BUSY tech should be dispatchable");
        assertEquals(1L, result.getTechnicianId());
    }
}
