package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.dto.DispatchResult;
import com.maintenance.entity.DispatchRecord;
import com.maintenance.entity.Fault;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.Technician;
import com.maintenance.entity.TechnicianSkill;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.EventType;
import com.maintenance.enums.FaultStatus;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.enums.WorkOrderStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.DispatchRecordMapper;
import com.maintenance.mapper.EquipmentMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.TechnicianSkillMapper;
import com.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkOrderServiceTest {

    @Mock
    private WorkOrderMapper workOrderMapper;

    @Mock
    private DispatchRecordMapper dispatchRecordMapper;

    @Mock
    private FaultMapper faultMapper;

    @Mock
    private EquipmentMapper equipmentMapper;

    @Mock
    private TechnicianService technicianService;

    @Mock
    private TechnicianSkillMapper technicianSkillMapper;

    @Mock
    private SparePartService sparePartService;

    @Mock
    private DowntimeService downtimeService;

    @Mock
    private AutoDispatchService autoDispatchService;

    @Mock
    private LocalMessageQueue messageQueue;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private WorkOrderService workOrderService;

    // Shared test IDs
    private static final Long WORK_ORDER_ID = 100L;
    private static final Long OLD_TECH_ID = 10L;
    private static final Long NEW_TECH_ID = 20L;
    private static final Long FAULT_ID = 30L;
    private static final Long EQUIPMENT_ID = 40L;
    private static final Long PART_ID = 50L;

    private WorkOrder baseWorkOrder;
    private Fault baseFault;

    @BeforeEach
    void setUp() {
        baseWorkOrder = new WorkOrder();
        baseWorkOrder.setId(WORK_ORDER_ID);
        baseWorkOrder.setOrderCode("WO20260610120000001");
        baseWorkOrder.setFaultId(FAULT_ID);
        baseWorkOrder.setEquipmentId(EQUIPMENT_ID);
        baseWorkOrder.setTechnicianId(OLD_TECH_ID);
        baseWorkOrder.setPriority(2);
        baseWorkOrder.setReassignCount(0);
        baseWorkOrder.setEscalateCount(0);
        baseWorkOrder.setFaultDescription("Motor overheating");

        baseFault = new Fault();
        baseFault.setId(FAULT_ID);
        baseFault.setEquipmentType("CNC_MACHINE");
        baseFault.setFaultLevel(2);
        baseFault.setStatus(FaultStatus.PROCESSING.name());
    }

    // -----------------------------------------------------------------------
    // 1. reassign - fails when new technician is OFFLINE
    // -----------------------------------------------------------------------
    @Test
    void reassign_failsWhenNewTechnicianOffline() {
        baseWorkOrder.setStatus(WorkOrderStatus.ACCEPTED.name());
        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(baseWorkOrder);

        Technician offlineTech = new Technician();
        offlineTech.setId(NEW_TECH_ID);
        offlineTech.setName("Offline Tech");
        offlineTech.setAvailability(TechnicianAvailability.OFFLINE.name());
        when(technicianService.getById(NEW_TECH_ID)).thenReturn(offlineTech);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.reassign(WORK_ORDER_ID, NEW_TECH_ID, "need specialist"));

        assertTrue(ex.getMessage().contains(String.valueOf(NEW_TECH_ID)));

        verify(messageQueue).publish(eq(EventType.REASSIGN_FAILED.name()), anyMap());

        // Spare parts must NOT be released on failure
        verify(sparePartService, never()).releaseOccupationsByWorkOrder(anyLong());
        // Workload must NOT be modified on failure
        verify(technicianService, never()).decrementWorkload(anyLong());
        verify(technicianService, never()).incrementWorkload(anyLong());
    }

    // -----------------------------------------------------------------------
    // 2. reassign - fails when new technician lacks matching skill
    // -----------------------------------------------------------------------
    @Test
    void reassign_failsWhenNewTechnicianLacksSkill() {
        baseWorkOrder.setStatus(WorkOrderStatus.REPAIRING.name());
        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(baseWorkOrder);

        Technician availableTech = createAvailableTechnician(NEW_TECH_ID, "No-Skill Tech");
        when(technicianService.getById(NEW_TECH_ID)).thenReturn(availableTech);

        when(faultMapper.selectById(FAULT_ID)).thenReturn(baseFault);
        // No matching skill record exists
        when(technicianSkillMapper.selectByTechnicianAndType(NEW_TECH_ID, "CNC_MACHINE"))
                .thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.reassign(WORK_ORDER_ID, NEW_TECH_ID, "reassign needed"));

        assertTrue(ex.getMessage().contains("lacks skill") || ex.getMessage().contains("equipmentType"));

        verify(messageQueue).publish(eq(EventType.REASSIGN_FAILED.name()), anyMap());
        verify(sparePartService, never()).releaseOccupationsByWorkOrder(anyLong());
    }

    // -----------------------------------------------------------------------
    // 3. reassign - fails when certification level is insufficient
    // -----------------------------------------------------------------------
    @Test
    void reassign_failsWhenCertificationInsufficient() {
        baseWorkOrder.setStatus(WorkOrderStatus.ARRIVED.name());
        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(baseWorkOrder);

        Technician availableTech = createAvailableTechnician(NEW_TECH_ID, "Low-Cert Tech");
        when(technicianService.getById(NEW_TECH_ID)).thenReturn(availableTech);

        when(faultMapper.selectById(FAULT_ID)).thenReturn(baseFault); // faultLevel = 2

        TechnicianSkill lowSkill = new TechnicianSkill();
        lowSkill.setTechnicianId(NEW_TECH_ID);
        lowSkill.setEquipmentType("CNC_MACHINE");
        lowSkill.setProficiency(3);
        lowSkill.setCertifiedFaultLevel(1); // 1 < required 2
        when(technicianSkillMapper.selectByTechnicianAndType(NEW_TECH_ID, "CNC_MACHINE"))
                .thenReturn(lowSkill);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.reassign(WORK_ORDER_ID, NEW_TECH_ID, "cert too low"));

        assertTrue(ex.getMessage().contains("certification") || ex.getMessage().contains("insufficient"));

        verify(messageQueue).publish(eq(EventType.REASSIGN_FAILED.name()), anyMap());
        verify(sparePartService, never()).releaseOccupationsByWorkOrder(anyLong());
    }

    // -----------------------------------------------------------------------
    // 4. reassign - success releases spare parts and updates workload
    // -----------------------------------------------------------------------
    @Test
    void reassign_successReleasesSparePartsAndUpdatesWorkload() {
        baseWorkOrder.setStatus(WorkOrderStatus.REPAIRING.name());
        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(baseWorkOrder);

        Technician newTech = createAvailableTechnician(NEW_TECH_ID, "Qualified Tech");
        when(technicianService.getById(NEW_TECH_ID)).thenReturn(newTech);

        when(faultMapper.selectById(FAULT_ID)).thenReturn(baseFault); // faultLevel = 2

        TechnicianSkill matchingSkill = new TechnicianSkill();
        matchingSkill.setTechnicianId(NEW_TECH_ID);
        matchingSkill.setEquipmentType("CNC_MACHINE");
        matchingSkill.setProficiency(4);
        matchingSkill.setCertifiedFaultLevel(3); // 3 >= required 2
        when(technicianSkillMapper.selectByTechnicianAndType(NEW_TECH_ID, "CNC_MACHINE"))
                .thenReturn(matchingSkill);

        // Old tech has no other active orders after decrement
        when(workOrderMapper.selectActiveByTechnicianId(OLD_TECH_ID))
                .thenReturn(Collections.emptyList());

        WorkOrder result = workOrderService.reassign(WORK_ORDER_ID, NEW_TECH_ID, "better fit");

        // Spare parts must be released
        verify(sparePartService).releaseOccupationsByWorkOrder(WORK_ORDER_ID);

        // Old technician workload decremented
        verify(technicianService).decrementWorkload(OLD_TECH_ID);

        // Old technician set back to AVAILABLE since no other active orders
        verify(technicianService).updateAvailability(OLD_TECH_ID, TechnicianAvailability.AVAILABLE.name());

        // New technician workload incremented
        verify(technicianService).incrementWorkload(NEW_TECH_ID);

        // New dispatch record created with type REASSIGN
        ArgumentCaptor<DispatchRecord> dispatchCaptor = ArgumentCaptor.forClass(DispatchRecord.class);
        verify(dispatchRecordMapper).insert(dispatchCaptor.capture());
        DispatchRecord newDispatch = dispatchCaptor.getValue();
        assertEquals(WORK_ORDER_ID, newDispatch.getWorkOrderId());
        assertEquals(NEW_TECH_ID, newDispatch.getTechnicianId());
        assertEquals("REASSIGN", newDispatch.getDispatchType());
        assertEquals(0, newDispatch.getIsAccepted());

        // Final work order state: new tech, CREATED, reassignCount incremented
        assertEquals(NEW_TECH_ID, result.getTechnicianId());
        assertEquals(WorkOrderStatus.CREATED.name(), result.getStatus());
        assertEquals(1, result.getReassignCount());

        // WORK_ORDER_REASSIGNED event published (not REASSIGN_FAILED)
        verify(messageQueue).publish(eq(EventType.WORK_ORDER_REASSIGNED.name()), anyMap());
        verify(messageQueue, never()).publish(eq(EventType.REASSIGN_FAILED.name()), anyMap());
    }

    // -----------------------------------------------------------------------
    // 5. reassign - fails from terminal state (COMPLETED)
    // -----------------------------------------------------------------------
    @Test
    void reassign_failsFromTerminalState() {
        baseWorkOrder.setStatus(WorkOrderStatus.COMPLETED.name());
        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(baseWorkOrder);

        // COMPLETED only allows REWORK and CLOSED_ABNORMAL, not REASSIGNED
        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.reassign(WORK_ORDER_ID, NEW_TECH_ID, "too late"));

        assertTrue(ex.getMessage().contains("Cannot reassign") || ex.getMessage().contains("COMPLETED"));

        // Should fail before any technician lookup
        verify(technicianService, never()).getById(anyLong());
        verify(sparePartService, never()).releaseOccupationsByWorkOrder(anyLong());
        verify(messageQueue, never()).publish(eq(EventType.REASSIGN_FAILED.name()), anyMap());
    }

    // -----------------------------------------------------------------------
    // 6. rework - creates new linked work order
    // -----------------------------------------------------------------------
    @Test
    void rework_createsNewLinkedWorkOrder() {
        baseWorkOrder.setStatus(WorkOrderStatus.COMPLETED.name());
        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(baseWorkOrder);
        when(faultMapper.selectById(FAULT_ID)).thenReturn(baseFault);

        // faultLevel = 2, so autoDispatch (not emergencyDispatch) is used
        DispatchResult dispatchResult = DispatchResult.builder()
                .success(true)
                .message("Dispatched")
                .build();
        when(autoDispatchService.autoDispatch(any(WorkOrder.class), eq(baseFault)))
                .thenReturn(dispatchResult);

        WorkOrder newOrder = workOrderService.rework(WORK_ORDER_ID, "defect reappeared");

        // Original order marked as REWORK
        verify(workOrderMapper, atLeastOnce()).updateById(argThat(wo ->
                wo.getId() != null && wo.getId().equals(WORK_ORDER_ID)
                        && WorkOrderStatus.REWORK.name().equals(wo.getStatus())));

        // New work order inserted
        ArgumentCaptor<WorkOrder> insertCaptor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderMapper).insert(insertCaptor.capture());
        WorkOrder insertedOrder = insertCaptor.getValue();
        assertEquals(1, insertedOrder.getIsRerepair());
        assertEquals(WORK_ORDER_ID, insertedOrder.getOriginalOrderId());
        assertEquals(WorkOrderStatus.CREATED.name(), insertedOrder.getStatus());
        assertEquals(FAULT_ID, insertedOrder.getFaultId());
        assertEquals(EQUIPMENT_ID, insertedOrder.getEquipmentId());
        assertEquals(baseWorkOrder.getPriority(), insertedOrder.getPriority());
        assertEquals(0, insertedOrder.getReassignCount());
        assertEquals(0, insertedOrder.getEscalateCount());

        // Fault re-opened to PROCESSING
        verify(faultMapper).updateStatus(FAULT_ID, FaultStatus.PROCESSING.name());

        // Downtime started for the new order
        verify(downtimeService).startDowntime(eq(EQUIPMENT_ID), any(), eq(FAULT_ID));

        // WORK_ORDER_REWORK event published
        verify(messageQueue).publish(eq(EventType.WORK_ORDER_REWORK.name()), anyMap());
    }

    // -----------------------------------------------------------------------
    // 7. rework - fails from non-COMPLETED state (REPAIRING)
    // -----------------------------------------------------------------------
    @Test
    void rework_failsFromNonCompletedState() {
        baseWorkOrder.setStatus(WorkOrderStatus.REPAIRING.name());
        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(baseWorkOrder);

        // REPAIRING allows COMPLETED, SUSPENDED, REASSIGNED, CLOSED_ABNORMAL but NOT REWORK
        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.rework(WORK_ORDER_ID, "premature rework"));

        assertTrue(ex.getMessage().contains("Cannot transition") || ex.getMessage().contains("REWORK"));

        // Nothing should happen
        verify(workOrderMapper, never()).insert(any(WorkOrder.class));
        verify(faultMapper, never()).updateStatus(anyLong(), anyString());
        verify(downtimeService, never()).startDowntime(anyLong(), anyLong(), anyLong());
    }

    // -----------------------------------------------------------------------
    // 8. complete - consumes parts, calculates cost, ends downtime
    // -----------------------------------------------------------------------
    @Test
    void complete_consumesPartsAndEndsDowntime() {
        baseWorkOrder.setStatus(WorkOrderStatus.REPAIRING.name());
        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(baseWorkOrder);

        // Prepare occupied spare parts (already consumed after consumeAllByWorkOrder)
        SparePartOccupation occ1 = new SparePartOccupation();
        occ1.setId(1L);
        occ1.setWorkOrderId(WORK_ORDER_ID);
        occ1.setPartId(PART_ID);
        occ1.setQuantity(3);
        occ1.setStatus(OccupationStatus.CONSUMED.name());

        SparePartOccupation occ2 = new SparePartOccupation();
        occ2.setId(2L);
        occ2.setWorkOrderId(WORK_ORDER_ID);
        occ2.setPartId(51L);
        occ2.setQuantity(1);
        occ2.setStatus(OccupationStatus.CONSUMED.name());

        when(sparePartService.getOccupationsByWorkOrder(WORK_ORDER_ID))
                .thenReturn(List.of(occ1, occ2));

        SparePart part1 = new SparePart();
        part1.setId(PART_ID);
        part1.setUnitPrice(new BigDecimal("25.00"));
        when(sparePartService.getById(PART_ID)).thenReturn(part1);

        SparePart part2 = new SparePart();
        part2.setId(51L);
        part2.setUnitPrice(new BigDecimal("100.00"));
        when(sparePartService.getById(51L)).thenReturn(part2);

        // Technician has no other active orders after this one completes
        when(workOrderMapper.selectActiveByTechnicianId(OLD_TECH_ID))
                .thenReturn(Collections.emptyList());

        BigDecimal laborCost = new BigDecimal("200.00");
        WorkOrder result = workOrderService.complete(WORK_ORDER_ID, "Replaced bearings", laborCost);

        // Parts consumed
        verify(sparePartService).consumeAllByWorkOrder(WORK_ORDER_ID);

        // Parts cost calculated: 3 * 25.00 + 1 * 100.00 = 175.00
        BigDecimal expectedPartsCost = new BigDecimal("175.00");
        assertEquals(0, expectedPartsCost.compareTo(result.getPartsCost()));

        // Labor cost set
        assertEquals(0, laborCost.compareTo(result.getLaborCost()));

        // Status set to COMPLETED
        assertEquals(WorkOrderStatus.COMPLETED.name(), result.getStatus());
        assertNotNull(result.getCompletedAt());

        // Technician workload decremented
        verify(technicianService).decrementWorkload(OLD_TECH_ID);

        // Technician set to AVAILABLE (no other active orders)
        verify(technicianService).updateAvailability(OLD_TECH_ID, TechnicianAvailability.AVAILABLE.name());

        // Downtime ended
        verify(downtimeService).endDowntime(EQUIPMENT_ID, WORK_ORDER_ID);

        // Fault resolved
        verify(faultMapper).updateStatus(FAULT_ID, FaultStatus.RESOLVED.name());

        // Equipment set to RUNNING
        verify(equipmentMapper).updateStatus(EQUIPMENT_ID, "RUNNING");

        // REPAIR_COMPLETED event published
        verify(messageQueue).publish(eq(EventType.REPAIR_COMPLETED.name()), anyMap());
    }

    // -----------------------------------------------------------------------
    // 9. closeAbnormal - releases all resources
    // -----------------------------------------------------------------------
    @Test
    void closeAbnormal_releasesAllResources() {
        baseWorkOrder.setStatus(WorkOrderStatus.REPAIRING.name());
        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(baseWorkOrder);

        // Technician has no other active orders
        when(workOrderMapper.selectActiveByTechnicianId(OLD_TECH_ID))
                .thenReturn(Collections.emptyList());

        WorkOrder result = workOrderService.closeAbnormal(WORK_ORDER_ID, "equipment scrapped");

        // Spare parts released
        verify(sparePartService).releaseOccupationsByWorkOrder(WORK_ORDER_ID);

        // Technician workload decremented
        verify(technicianService).decrementWorkload(OLD_TECH_ID);

        // Technician set to AVAILABLE
        verify(technicianService).updateAvailability(OLD_TECH_ID, TechnicianAvailability.AVAILABLE.name());

        // Fault status set to CLOSED
        verify(faultMapper).updateStatus(FAULT_ID, FaultStatus.CLOSED.name());

        // Downtime ended
        verify(downtimeService).endDowntime(EQUIPMENT_ID, WORK_ORDER_ID);

        // Status set to CLOSED_ABNORMAL
        assertEquals(WorkOrderStatus.CLOSED_ABNORMAL.name(), result.getStatus());

        // WORK_ORDER_CLOSED event published
        verify(messageQueue).publish(eq(EventType.WORK_ORDER_CLOSED.name()), anyMap());
    }

    // -----------------------------------------------------------------------
    // 10. suspend - from REPAIRING to SUSPENDED
    // -----------------------------------------------------------------------
    @Test
    void suspend_fromRepairingToSuspended() {
        baseWorkOrder.setStatus(WorkOrderStatus.REPAIRING.name());
        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(baseWorkOrder);

        WorkOrder result = workOrderService.suspend(WORK_ORDER_ID, "waiting for parts");

        // Status updated to SUSPENDED
        assertEquals(WorkOrderStatus.SUSPENDED.name(), result.getStatus());
        assertNotNull(result.getSuspendedAt());
        assertNotNull(result.getUpdatedAt());

        // Work order persisted
        verify(workOrderMapper).updateById(argThat(wo ->
                WorkOrderStatus.SUSPENDED.name().equals(wo.getStatus())));

        // STATUS_CHANGED event published
        verify(messageQueue).publish(eq(EventType.STATUS_CHANGED.name()), anyMap());

        // Audit logged
        verify(auditService).log(eq("WORK_ORDER"), eq("SUSPEND"), eq("WorkOrder"),
                eq(WORK_ORDER_ID), anyString(), contains("suspended"));
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private Technician createAvailableTechnician(Long id, String name) {
        Technician tech = new Technician();
        tech.setId(id);
        tech.setName(name);
        tech.setAvailability(TechnicianAvailability.AVAILABLE.name());
        tech.setCurrentWorkload(1);
        return tech;
    }
}
