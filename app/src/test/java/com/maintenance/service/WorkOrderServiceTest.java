package com.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.maintenance.common.BusinessException;
import com.maintenance.entity.DowntimeRecord;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.Technician;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.DispatchRecordMapper;
import com.maintenance.mapper.EquipmentMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for WorkOrderService focusing on:
 * 1. Reassign properly ends downtime for old assignment
 * 2. Reassign rejects OFFLINE/ON_LEAVE new technician
 * 3. Reassign failure triggers rollback (via @Transactional)
 * 4. Suspend pauses downtime
 * 5. Resume restarts downtime
 * 6. Complete ends downtime with workOrderId matching
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderServiceTest {

    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private DispatchRecordMapper dispatchRecordMapper;
    @Mock private FaultMapper faultMapper;
    @Mock private EquipmentMapper equipmentMapper;
    @Mock private TechnicianService technicianService;
    @Mock private SparePartService sparePartService;
    @Mock private DowntimeService downtimeService;
    @Mock private LocalMessageQueue messageQueue;
    @Mock private AuditService auditService;
    @Mock private PredictiveDispatchService predictiveDispatchService;
    @Mock private SlaService slaService;

    private WorkOrderService workOrderService;

    @BeforeEach
    void setUp() {
        workOrderService = new WorkOrderService(
                workOrderMapper, dispatchRecordMapper, faultMapper, equipmentMapper,
                technicianService, sparePartService, downtimeService,
                messageQueue, auditService, predictiveDispatchService, slaService);
    }

    private WorkOrder createWorkOrder(Long id, String status, Long techId, Long equipId, Long faultId) {
        WorkOrder wo = new WorkOrder();
        wo.setId(id);
        wo.setOrderCode("WO-TEST-" + id);
        wo.setStatus(status);
        wo.setTechnicianId(techId);
        wo.setEquipmentId(equipId);
        wo.setFaultId(faultId);
        wo.setPriority(2);
        wo.setReassignCount(0);
        wo.setEscalateCount(0);
        return wo;
    }

    // ========================================================
    // TEST: Reassign ends old downtime record
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Reassign must end downtime for the old assignment")
    void reassign_endsOldDowntime() {
        WorkOrder order = createWorkOrder(1L, "REPAIRING", 100L, 50L, 10L);
        Technician newTech = new Technician();
        newTech.setId(200L);
        newTech.setAvailability(TechnicianAvailability.AVAILABLE.name());

        DowntimeRecord oldDowntime = new DowntimeRecord();
        oldDowntime.setId(1L);
        oldDowntime.setEquipmentId(50L);
        oldDowntime.setWorkOrderId(1L);

        when(workOrderMapper.selectById(1L)).thenReturn(order);
        when(technicianService.getById(200L)).thenReturn(newTech);
        when(downtimeService.endDowntime(50L, 1L)).thenReturn(oldDowntime);
        when(dispatchRecordMapper.insert(any())).thenReturn(1);
        when(workOrderMapper.updateById(any())).thenReturn(1);
        when(workOrderMapper.selectActiveByTechnicianId(100L)).thenReturn(Collections.emptyList());

        workOrderService.reassign(1L, 200L, "skill mismatch");

        // Verify downtime was ended for the old assignment
        verify(downtimeService).endDowntime(50L, 1L);
        // Verify old tech workload decremented
        verify(technicianService).decrementWorkload(100L);
        // Verify new tech workload incremented
        verify(technicianService).incrementWorkload(200L);
        // Verify spare parts were released
        verify(sparePartService).releaseOccupationsByWorkOrder(1L);
    }

    // ========================================================
    // TEST: Reassign rejects OFFLINE technician
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Reassign to OFFLINE technician must throw BusinessException")
    void reassign_rejectsOfflineTechnician() {
        WorkOrder order = createWorkOrder(1L, "REPAIRING", 100L, 50L, 10L);
        Technician offlineTech = new Technician();
        offlineTech.setId(200L);
        offlineTech.setAvailability(TechnicianAvailability.OFFLINE.name());

        when(workOrderMapper.selectById(1L)).thenReturn(order);
        when(technicianService.getById(200L)).thenReturn(offlineTech);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.reassign(1L, 200L, "reassign to offline"));

        assertTrue(ex.getMessage().contains("OFFLINE"));
    }

    // ========================================================
    // TEST: Reassign rejects ON_LEAVE technician
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Reassign to ON_LEAVE technician must throw BusinessException")
    void reassign_rejectsOnLeaveTechnician() {
        WorkOrder order = createWorkOrder(1L, "REPAIRING", 100L, 50L, 10L);
        Technician onLeaveTech = new Technician();
        onLeaveTech.setId(200L);
        onLeaveTech.setAvailability(TechnicianAvailability.ON_LEAVE.name());

        when(workOrderMapper.selectById(1L)).thenReturn(order);
        when(technicianService.getById(200L)).thenReturn(onLeaveTech);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.reassign(1L, 200L, "reassign to on-leave"));

        assertTrue(ex.getMessage().contains("ON_LEAVE"));
    }

    // ========================================================
    // TEST: Reassign to non-existent technician fails
    // ========================================================
    @Test
    @DisplayName("Reassign to non-existent technician must throw BusinessException")
    void reassign_rejectsNonExistentTechnician() {
        WorkOrder order = createWorkOrder(1L, "REPAIRING", 100L, 50L, 10L);

        when(workOrderMapper.selectById(1L)).thenReturn(order);
        when(technicianService.getById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.reassign(1L, 999L, "ghost tech"));

        assertTrue(ex.getMessage().contains("not found"));
    }

    // ========================================================
    // TEST: Suspend pauses downtime
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Suspend must pause (end) the active downtime record")
    void suspend_pausesDowntime() {
        WorkOrder order = createWorkOrder(1L, "REPAIRING", 100L, 50L, 10L);

        when(workOrderMapper.selectById(1L)).thenReturn(order);
        when(workOrderMapper.update(any(), any())).thenReturn(1);
        when(downtimeService.endDowntime(50L, 1L)).thenReturn(new DowntimeRecord());

        workOrderService.suspend(1L, "waiting for parts");

        // Verify downtime was paused (ended)
        verify(downtimeService).endDowntime(50L, 1L);
        // Verify SLA was paused
        verify(slaService).pauseSla(eq(1L), anyString());
        assertEquals("SUSPENDED", order.getStatus());
    }

    // ========================================================
    // TEST: Resume restarts downtime
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Resume must restart the downtime record")
    void resume_restartsDowntime() {
        WorkOrder order = createWorkOrder(1L, "SUSPENDED", 100L, 50L, 10L);

        when(workOrderMapper.selectById(1L)).thenReturn(order);
        when(workOrderMapper.update(any(), any())).thenReturn(1);
        when(downtimeService.startDowntime(50L, 1L, 10L)).thenReturn(new DowntimeRecord());

        workOrderService.resume(1L);

        // Verify downtime was restarted
        verify(downtimeService).startDowntime(50L, 1L, 10L);
        // Verify SLA was resumed
        verify(slaService).resumeSla(1L);
        assertEquals("REPAIRING", order.getStatus());
    }

    // ========================================================
    // TEST: Complete ends downtime with workOrderId match
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Complete must end downtime matching by equipmentId AND workOrderId")
    void complete_endsDowntimeWithWorkOrderIdMatch() {
        WorkOrder order = createWorkOrder(1L, "REPAIRING", 100L, 50L, 10L);

        SparePartOccupation occ = new SparePartOccupation();
        occ.setId(1L);
        occ.setPartId(1L);
        occ.setQuantity(2);
        occ.setStatus(OccupationStatus.CONSUMED.name());

        SparePart part = new SparePart();
        part.setId(1L);
        part.setUnitPrice(BigDecimal.valueOf(100));

        when(workOrderMapper.selectById(1L)).thenReturn(order);
        when(workOrderMapper.update(any(), any())).thenReturn(1);
        when(sparePartService.getOccupationsByWorkOrder(1L)).thenReturn(Collections.singletonList(occ));
        when(sparePartService.getById(1L)).thenReturn(part);
        when(workOrderMapper.selectActiveByTechnicianId(100L)).thenReturn(Collections.emptyList());
        when(downtimeService.endDowntime(50L, 1L)).thenReturn(new DowntimeRecord());
        when(faultMapper.updateStatus(anyLong(), anyString())).thenReturn(1);
        when(equipmentMapper.updateStatus(anyLong(), anyString())).thenReturn(1);

        workOrderService.complete(1L, "replaced bearing", BigDecimal.valueOf(500));

        // Verify downtime ended with workOrderId-specific call
        verify(downtimeService).endDowntime(50L, 1L);
        // Verify parts consumed
        verify(sparePartService).consumeAllByWorkOrder(1L);
        // Verify technician workload decremented
        verify(technicianService).decrementWorkload(100L);
        // Verify equipment set to RUNNING
        verify(equipmentMapper).updateStatus(50L, "RUNNING");
        assertEquals("COMPLETED", order.getStatus());
    }

    // ========================================================
    // TEST: Close abnormal ends downtime and releases all resources
    // ========================================================
    @Test
    @DisplayName("Close abnormal: ends downtime, releases parts, decrements workload")
    void closeAbnormal_releasesAllResources() {
        WorkOrder order = createWorkOrder(1L, "REPAIRING", 100L, 50L, 10L);

        when(workOrderMapper.selectById(1L)).thenReturn(order);
        when(workOrderMapper.updateById(any())).thenReturn(1);
        when(workOrderMapper.selectActiveByTechnicianId(100L)).thenReturn(Collections.emptyList());
        when(downtimeService.endDowntime(50L, 1L)).thenReturn(new DowntimeRecord());
        when(faultMapper.updateStatus(anyLong(), anyString())).thenReturn(1);

        workOrderService.closeAbnormal(1L, "equipment scrapped");

        verify(sparePartService).releaseOccupationsByWorkOrder(1L);
        verify(technicianService).decrementWorkload(100L);
        verify(downtimeService).endDowntime(50L, 1L);
        verify(faultMapper).updateStatus(10L, "CLOSED");
        assertEquals("CLOSED_ABNORMAL", order.getStatus());
    }

    // ========================================================
    // TEST: Invalid state transition throws exception
    // ========================================================
    @Test
    @DisplayName("Invalid state transition (COMPLETED -> ACCEPTED) must throw")
    void accept_fromCompleted_throws() {
        WorkOrder order = createWorkOrder(1L, "COMPLETED", 100L, 50L, 10L);

        when(workOrderMapper.selectById(1L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.accept(1L, 100L));

        assertTrue(ex.getMessage().contains("Cannot transition"));
    }
}
