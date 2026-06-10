package com.maintenance.service;

import com.maintenance.dto.DispatchResult;
import com.maintenance.entity.Fault;
import com.maintenance.entity.Technician;
import com.maintenance.entity.TechnicianSkill;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.enums.WorkOrderStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.DispatchRecordMapper;
import com.maintenance.mapper.TechnicianMapper;
import com.maintenance.mapper.TechnicianSkillMapper;
import com.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoDispatchServiceTest {

    @Mock
    private TechnicianMapper technicianMapper;

    @Mock
    private TechnicianSkillMapper technicianSkillMapper;

    @Mock
    private WorkOrderMapper workOrderMapper;

    @Mock
    private DispatchRecordMapper dispatchRecordMapper;

    @Mock
    private LocalMessageQueue messageQueue;

    @Mock
    private AuditService auditService;

    @Mock
    private TechnicianService technicianService;

    @Mock
    private SparePartService sparePartService;

    @InjectMocks
    private AutoDispatchService autoDispatchService;

    // -------------------------------------------------------------------------
    // Helper methods for creating test fixtures
    // -------------------------------------------------------------------------

    private Technician createTechnician(Long id, String name, String availability, int currentWorkload) {
        Technician tech = new Technician();
        tech.setId(id);
        tech.setName(name);
        tech.setAvailability(availability);
        tech.setCurrentWorkload(currentWorkload);
        return tech;
    }

    private TechnicianSkill createSkill(Long technicianId, String equipmentType, int proficiency, int certifiedFaultLevel) {
        TechnicianSkill skill = new TechnicianSkill();
        skill.setTechnicianId(technicianId);
        skill.setEquipmentType(equipmentType);
        skill.setProficiency(proficiency);
        skill.setCertifiedFaultLevel(certifiedFaultLevel);
        return skill;
    }

    private WorkOrder createWorkOrder(Long id, String orderCode, int priority) {
        WorkOrder wo = new WorkOrder();
        wo.setId(id);
        wo.setOrderCode(orderCode);
        wo.setPriority(priority);
        return wo;
    }

    private WorkOrder createWorkOrder(Long id, String orderCode, int priority, String status) {
        WorkOrder wo = createWorkOrder(id, orderCode, priority);
        wo.setStatus(status);
        return wo;
    }

    private Fault createFault(Long id, String equipmentType, int faultLevel) {
        Fault fault = new Fault();
        fault.setId(id);
        fault.setEquipmentType(equipmentType);
        fault.setFaultLevel(faultLevel);
        return fault;
    }

    /**
     * Stubs the performance-score query used during scoring.
     * Returns 0 completed orders by default so performance score = 15.
     */
    private void stubPerformanceCount(Long technicianId, int completedCount) {
        when(workOrderMapper.countByTechnicianAndStatus(eq(technicianId), eq(WorkOrderStatus.COMPLETED.name())))
                .thenReturn(completedCount);
    }

    // -------------------------------------------------------------------------
    // 1. autoDispatch_filtersOutOfflineTechnicians
    // -------------------------------------------------------------------------

    @Test
    void autoDispatch_filtersOutOfflineTechnicians() {
        // Arrange: OFFLINE tech has top-tier skills, but should be filtered out.
        //          AVAILABLE tech with lower skills should be selected instead.
        Technician offlineTech = createTechnician(1L, "OfflineTech", TechnicianAvailability.OFFLINE.name(), 0);
        Technician availableTech = createTechnician(2L, "AvailableTech", TechnicianAvailability.AVAILABLE.name(), 0);

        WorkOrder workOrder = createWorkOrder(100L, "WO-100", 2);
        Fault fault = createFault(10L, "CNC_MACHINE", 2);

        when(technicianMapper.selectList(null)).thenReturn(Arrays.asList(offlineTech, availableTech));

        // OFFLINE tech should never have skill checked; AVAILABLE tech has matching skill
        TechnicianSkill availableSkill = createSkill(2L, "CNC_MACHINE", 3, 3);
        when(technicianSkillMapper.selectByTechnicianAndType(2L, "CNC_MACHINE")).thenReturn(availableSkill);

        stubPerformanceCount(2L, 0);

        // Act
        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTechnicianId()).isEqualTo(2L);
        assertThat(result.getTechnicianName()).isEqualTo("AvailableTech");

        // Verify that the skill mapper was never called for the OFFLINE technician
        verify(technicianSkillMapper, never()).selectByTechnicianAndType(eq(1L), anyString());
        verify(technicianService).incrementWorkload(2L);
    }

    // -------------------------------------------------------------------------
    // 2. autoDispatch_filtersOutOnLeaveTechnicians
    // -------------------------------------------------------------------------

    @Test
    void autoDispatch_filtersOutOnLeaveTechnicians() {
        // Arrange: ON_LEAVE tech should be filtered out
        Technician onLeaveTech = createTechnician(1L, "OnLeaveTech", TechnicianAvailability.ON_LEAVE.name(), 0);
        Technician availableTech = createTechnician(2L, "AvailableTech", TechnicianAvailability.AVAILABLE.name(), 0);

        WorkOrder workOrder = createWorkOrder(100L, "WO-100", 2);
        Fault fault = createFault(10L, "CNC_MACHINE", 2);

        when(technicianMapper.selectList(null)).thenReturn(Arrays.asList(onLeaveTech, availableTech));

        TechnicianSkill availableSkill = createSkill(2L, "CNC_MACHINE", 4, 3);
        when(technicianSkillMapper.selectByTechnicianAndType(2L, "CNC_MACHINE")).thenReturn(availableSkill);

        stubPerformanceCount(2L, 0);

        // Act
        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTechnicianId()).isEqualTo(2L);

        // ON_LEAVE tech never queried for skills
        verify(technicianSkillMapper, never()).selectByTechnicianAndType(eq(1L), anyString());
    }

    // -------------------------------------------------------------------------
    // 3. autoDispatch_filtersOutTechnicianWithoutMatchingSkill
    // -------------------------------------------------------------------------

    @Test
    void autoDispatch_filtersOutTechnicianWithoutMatchingSkill() {
        // Arrange: Tech 1 has no skill record for the required equipment type.
        //          Tech 2 has the matching skill.
        Technician techNoSkill = createTechnician(1L, "NoSkillTech", TechnicianAvailability.AVAILABLE.name(), 0);
        Technician techWithSkill = createTechnician(2L, "SkilledTech", TechnicianAvailability.AVAILABLE.name(), 0);

        WorkOrder workOrder = createWorkOrder(100L, "WO-100", 2);
        Fault fault = createFault(10L, "CONVEYOR", 1);

        when(technicianMapper.selectList(null)).thenReturn(Arrays.asList(techNoSkill, techWithSkill));

        // Tech 1 returns null (no skill for CONVEYOR)
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CONVEYOR")).thenReturn(null);
        // Tech 2 has the skill
        TechnicianSkill matchingSkill = createSkill(2L, "CONVEYOR", 3, 2);
        when(technicianSkillMapper.selectByTechnicianAndType(2L, "CONVEYOR")).thenReturn(matchingSkill);

        stubPerformanceCount(2L, 0);

        // Act
        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTechnicianId()).isEqualTo(2L);
        assertThat(result.getTechnicianName()).isEqualTo("SkilledTech");
    }

    // -------------------------------------------------------------------------
    // 4. autoDispatch_filtersOutTechnicianWithInsufficientCertification
    // -------------------------------------------------------------------------

    @Test
    void autoDispatch_filtersOutTechnicianWithInsufficientCertification() {
        // Arrange: Tech 1 has certifiedFaultLevel 1, but fault is level 3 -> filtered out.
        //          Tech 2 has certifiedFaultLevel 4 -> sufficient.
        Technician lowCertTech = createTechnician(1L, "LowCertTech", TechnicianAvailability.AVAILABLE.name(), 0);
        Technician highCertTech = createTechnician(2L, "HighCertTech", TechnicianAvailability.AVAILABLE.name(), 0);

        WorkOrder workOrder = createWorkOrder(100L, "WO-100", 2);
        Fault fault = createFault(10L, "ROBOT_ARM", 3);

        when(technicianMapper.selectList(null)).thenReturn(Arrays.asList(lowCertTech, highCertTech));

        // Tech 1 has matching equipment type but certifiedFaultLevel 1 < faultLevel 3
        TechnicianSkill lowCertSkill = createSkill(1L, "ROBOT_ARM", 5, 1);
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "ROBOT_ARM")).thenReturn(lowCertSkill);

        // Tech 2 has certifiedFaultLevel 4 >= faultLevel 3
        TechnicianSkill highCertSkill = createSkill(2L, "ROBOT_ARM", 3, 4);
        when(technicianSkillMapper.selectByTechnicianAndType(2L, "ROBOT_ARM")).thenReturn(highCertSkill);

        stubPerformanceCount(2L, 0);

        // Act
        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTechnicianId()).isEqualTo(2L);
        assertThat(result.getTechnicianName()).isEqualTo("HighCertTech");

        // Verify workload was incremented only for the selected tech
        verify(technicianService).incrementWorkload(2L);
        verify(technicianService, never()).incrementWorkload(1L);
    }

    // -------------------------------------------------------------------------
    // 5. autoDispatch_selectsHighestScoringQualifiedTechnician
    // -------------------------------------------------------------------------

    @Test
    void autoDispatch_selectsHighestScoringQualifiedTechnician() {
        // Arrange: Two qualified techs with different scores.
        //   Tech A: AVAILABLE (25 pts), workload=0 (25 pts), proficiency=5 (30 pts skill), certMargin=2 (20 pts)
        //   Tech B: BUSY (10 pts), workload=3 (10 pts), proficiency=1 (22 pts skill), certMargin=0 (10 pts)
        //   Tech A should have a much higher score.
        Technician techA = createTechnician(1L, "TopTech", TechnicianAvailability.AVAILABLE.name(), 0);
        Technician techB = createTechnician(2L, "WeakerTech", TechnicianAvailability.BUSY.name(), 3);

        WorkOrder workOrder = createWorkOrder(100L, "WO-100", 2);
        Fault fault = createFault(10L, "PRESS", 2);

        when(technicianMapper.selectList(null)).thenReturn(Arrays.asList(techB, techA));

        TechnicianSkill skillA = createSkill(1L, "PRESS", 5, 4);
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "PRESS")).thenReturn(skillA);

        TechnicianSkill skillB = createSkill(2L, "PRESS", 1, 2);
        when(technicianSkillMapper.selectByTechnicianAndType(2L, "PRESS")).thenReturn(skillB);

        stubPerformanceCount(1L, 0);
        stubPerformanceCount(2L, 0);

        // Act
        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        // Assert: Tech A (id=1) should be selected as the highest scorer
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTechnicianId()).isEqualTo(1L);
        assertThat(result.getTechnicianName()).isEqualTo("TopTech");
        assertThat(result.getDispatchScore()).isNotNull();

        // Verify dispatch record was created and workload incremented for the winner
        verify(dispatchRecordMapper).insert(any());
        verify(technicianService).incrementWorkload(1L);
        verify(messageQueue).publish(eq("DISPATCH_DONE"), any());
    }

    // -------------------------------------------------------------------------
    // 6. autoDispatch_returnsFailWhenNoTechniciansAvailable
    // -------------------------------------------------------------------------

    @Test
    void autoDispatch_returnsFailWhenNoTechniciansAvailable() {
        // Arrange: All technicians are filtered out (all OFFLINE)
        Technician offlineTech1 = createTechnician(1L, "Tech1", TechnicianAvailability.OFFLINE.name(), 0);
        Technician offlineTech2 = createTechnician(2L, "Tech2", TechnicianAvailability.ON_LEAVE.name(), 0);

        WorkOrder workOrder = createWorkOrder(100L, "WO-100", 2);
        Fault fault = createFault(10L, "CNC_MACHINE", 2);

        when(technicianMapper.selectList(null)).thenReturn(Arrays.asList(offlineTech1, offlineTech2));

        // Act
        DispatchResult result = autoDispatchService.autoDispatch(workOrder, fault);

        // Assert
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isNotBlank();

        // Verify no dispatch record was created, no workload change, no event published
        verify(dispatchRecordMapper, never()).insert(any());
        verify(technicianService, never()).incrementWorkload(anyLong());
        verify(messageQueue, never()).publish(eq("DISPATCH_DONE"), any());
    }

    // -------------------------------------------------------------------------
    // 7. emergencyDispatch_preemptsLowPriorityOrder
    // -------------------------------------------------------------------------

    @Test
    void emergencyDispatch_preemptsLowPriorityOrder() {
        // Arrange: autoDispatch fails (empty list first), then preemption finds a candidate.
        //   Emergency order has priority=4. Preemptable order has priority=1 (lower).
        Technician preemptableTech = createTechnician(1L, "BusyTech", TechnicianAvailability.BUSY.name(), 1);

        // First call (autoDispatch) -> empty list -> fail
        // Second call (emergencyDispatch's own loop) -> tech with active order
        when(technicianMapper.selectList(null))
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of(preemptableTech));

        WorkOrder emergencyOrder = createWorkOrder(200L, "WO-EMR-200", 4);
        Fault emergencyFault = createFault(20L, "CNC_MACHINE", 2);

        // Matching skill for the preemptable tech in the emergency dispatch loop
        TechnicianSkill skill = createSkill(1L, "CNC_MACHINE", 4, 3);
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC_MACHINE")).thenReturn(skill);

        // The preemptable tech has a low-priority active order
        WorkOrder lowPriorityOrder = createWorkOrder(50L, "WO-LOW-50", 1, WorkOrderStatus.REPAIRING.name());
        lowPriorityOrder.setTechnicianId(1L);
        lowPriorityOrder.setEquipmentId(500L);
        when(workOrderMapper.selectActiveByTechnicianId(1L)).thenReturn(List.of(lowPriorityOrder));

        // Act
        DispatchResult result = autoDispatchService.emergencyDispatch(emergencyOrder, emergencyFault);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTechnicianId()).isEqualTo(1L);
        assertThat(result.getTechnicianName()).isEqualTo("BusyTech");

        // Verify the preempted order was suspended
        assertThat(lowPriorityOrder.getStatus()).isEqualTo(WorkOrderStatus.SUSPENDED.name());
        verify(workOrderMapper).updateById(lowPriorityOrder);

        // Verify spare parts were released for the preempted order
        verify(sparePartService).releaseOccupationsByWorkOrder(50L);

        // Verify all three events were published
        verify(messageQueue).publish(eq("STATUS_CHANGED"), any());
        verify(messageQueue).publish(eq("DISPATCH_DONE"), any());
        verify(messageQueue).publish(eq("EMERGENCY_ALERT"), any());

        // Verify the emergency order was assigned to the preempted tech
        assertThat(emergencyOrder.getTechnicianId()).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // 8. emergencyDispatch_skipsAlreadySuspendedOrders
    // -------------------------------------------------------------------------

    @Test
    void emergencyDispatch_skipsAlreadySuspendedOrders() {
        // Arrange: The only active order for the tech is already SUSPENDED -> no preemption possible.
        Technician tech = createTechnician(1L, "BusyTech", TechnicianAvailability.BUSY.name(), 1);

        when(technicianMapper.selectList(null))
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of(tech));

        WorkOrder emergencyOrder = createWorkOrder(200L, "WO-EMR-200", 4);
        Fault emergencyFault = createFault(20L, "CNC_MACHINE", 2);

        TechnicianSkill skill = createSkill(1L, "CNC_MACHINE", 3, 3);
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC_MACHINE")).thenReturn(skill);

        // The only active order is already SUSPENDED
        WorkOrder suspendedOrder = createWorkOrder(50L, "WO-SUSP-50", 1, WorkOrderStatus.SUSPENDED.name());
        suspendedOrder.setTechnicianId(1L);
        when(workOrderMapper.selectActiveByTechnicianId(1L)).thenReturn(List.of(suspendedOrder));

        // Act
        DispatchResult result = autoDispatchService.emergencyDispatch(emergencyOrder, emergencyFault);

        // Assert: should fail because SUSPENDED orders are skipped
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("no preemptable order");

        // Verify no spare parts released and no order was suspended
        verify(sparePartService, never()).releaseOccupationsByWorkOrder(anyLong());
        verify(messageQueue, never()).publish(eq("STATUS_CHANGED"), any());
    }

    // -------------------------------------------------------------------------
    // 9. emergencyDispatch_failsWhenNoPreemptableOrder
    // -------------------------------------------------------------------------

    @Test
    void emergencyDispatch_failsWhenNoPreemptableOrder() {
        // Arrange: Tech has a matching skill but no active orders at all -> no preemption.
        Technician tech = createTechnician(1L, "IdleTech", TechnicianAvailability.AVAILABLE.name(), 0);

        when(technicianMapper.selectList(null))
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of(tech));

        WorkOrder emergencyOrder = createWorkOrder(200L, "WO-EMR-200", 4);
        Fault emergencyFault = createFault(20L, "CNC_MACHINE", 2);

        TechnicianSkill skill = createSkill(1L, "CNC_MACHINE", 3, 3);
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC_MACHINE")).thenReturn(skill);

        // No active orders for this technician
        when(workOrderMapper.selectActiveByTechnicianId(1L)).thenReturn(Collections.emptyList());

        // Act
        DispatchResult result = autoDispatchService.emergencyDispatch(emergencyOrder, emergencyFault);

        // Assert
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("no preemptable order");

        // Verify DISPATCH_FAILED was published
        verify(messageQueue).publish(eq("DISPATCH_FAILED"), any());

        // Verify no preemption side-effects
        verify(sparePartService, never()).releaseOccupationsByWorkOrder(anyLong());
        verify(messageQueue, never()).publish(eq("EMERGENCY_ALERT"), any());
    }

    // -------------------------------------------------------------------------
    // 10. emergencyDispatch_verifiesSkillMatchForPreemption
    // -------------------------------------------------------------------------

    @Test
    void emergencyDispatch_verifiesSkillMatchForPreemption() {
        // Arrange: Two technicians with active low-priority orders.
        //   Tech 1 has NO matching skill for the emergency fault -> skipped.
        //   Tech 2 HAS matching skill -> selected for preemption.
        Technician techNoSkill = createTechnician(1L, "NoSkillTech", TechnicianAvailability.BUSY.name(), 1);
        Technician techWithSkill = createTechnician(2L, "SkilledTech", TechnicianAvailability.BUSY.name(), 1);

        when(technicianMapper.selectList(null))
                .thenReturn(Collections.emptyList())
                .thenReturn(Arrays.asList(techNoSkill, techWithSkill));

        WorkOrder emergencyOrder = createWorkOrder(200L, "WO-EMR-200", 4);
        Fault emergencyFault = createFault(20L, "ROBOT_ARM", 2);

        // Tech 1 has no skill for ROBOT_ARM
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "ROBOT_ARM")).thenReturn(null);

        // Tech 2 has matching skill
        TechnicianSkill matchingSkill = createSkill(2L, "ROBOT_ARM", 3, 3);
        when(technicianSkillMapper.selectByTechnicianAndType(2L, "ROBOT_ARM")).thenReturn(matchingSkill);

        // Tech 2 has a preemptable low-priority order
        WorkOrder lowPriorityOrder = createWorkOrder(60L, "WO-LOW-60", 1, WorkOrderStatus.REPAIRING.name());
        lowPriorityOrder.setTechnicianId(2L);
        lowPriorityOrder.setEquipmentId(600L);
        when(workOrderMapper.selectActiveByTechnicianId(2L)).thenReturn(List.of(lowPriorityOrder));

        // Act
        DispatchResult result = autoDispatchService.emergencyDispatch(emergencyOrder, emergencyFault);

        // Assert: Tech 2 should be selected (the one with matching skill)
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTechnicianId()).isEqualTo(2L);
        assertThat(result.getTechnicianName()).isEqualTo("SkilledTech");

        // Verify preemption happened on Tech 2's order, not Tech 1's
        verify(sparePartService).releaseOccupationsByWorkOrder(60L);
        assertThat(lowPriorityOrder.getStatus()).isEqualTo(WorkOrderStatus.SUSPENDED.name());

        // Tech 1 was never checked for active orders because skill check failed
        verify(workOrderMapper, never()).selectActiveByTechnicianId(1L);
    }
}
