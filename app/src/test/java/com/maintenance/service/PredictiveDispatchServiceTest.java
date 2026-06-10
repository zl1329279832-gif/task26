package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.dto.DispatchPlanDTO;
import com.maintenance.dto.DispatchResult;
import com.maintenance.dto.PredictiveDispatchResult;
import com.maintenance.dto.PurchaseSuggestionDTO;
import com.maintenance.entity.DispatchPlan;
import com.maintenance.entity.Equipment;
import com.maintenance.entity.Fault;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.Technician;
import com.maintenance.entity.TechnicianSkill;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.DispatchPlanStatus;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.DispatchPlanMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.SparePartMapper;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PredictiveDispatchService focusing on:
 * 1. Multi-plan generation and ranking
 * 2. Emergency auto-select behavior for high fault levels
 * 3. Parts pre-reservation and shortage handling with purchase suggestions
 * 4. Plan selection, status transitions, and dispatch execution
 * 5. Hard filter enforcement (OFFLINE/ON_LEAVE exclusion)
 * 6. SLA deadline calculation by fault level
 */
@ExtendWith(MockitoExtension.class)
class PredictiveDispatchServiceTest {

    @Mock private TechnicianMapper technicianMapper;
    @Mock private TechnicianSkillMapper technicianSkillMapper;
    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private DispatchPlanMapper dispatchPlanMapper;
    @Mock private FaultMapper faultMapper;
    @Mock private SparePartMapper sparePartMapper;
    @Mock private SparePartService sparePartService;
    @Mock private AutoDispatchService autoDispatchService;
    @Mock private DowntimeService downtimeService;
    @Mock private WorkOrderService workOrderService;
    @Mock private AuditService auditService;
    @Mock private LocalMessageQueue messageQueue;
    @Mock private MaintenanceWebSocketHandler webSocketHandler;

    private PredictiveDispatchService predictiveDispatchService;

    @BeforeEach
    void setUp() {
        predictiveDispatchService = new PredictiveDispatchService(
                technicianMapper, technicianSkillMapper, workOrderMapper,
                dispatchPlanMapper, faultMapper, sparePartMapper,
                sparePartService, autoDispatchService, downtimeService,
                workOrderService, auditService, messageQueue, webSocketHandler);
    }

    // --- Helper methods ---

    private Technician createTechnician(Long id, String name, String employeeCode,
                                        String availability, int workload) {
        Technician tech = new Technician();
        tech.setId(id);
        tech.setName(name);
        tech.setEmployeeCode(employeeCode);
        tech.setAvailability(availability);
        tech.setCurrentWorkload(workload);
        tech.setSkillLevel(3);
        return tech;
    }

    private TechnicianSkill createSkill(Long techId, String equipmentType,
                                        int proficiency, int certLevel) {
        TechnicianSkill skill = new TechnicianSkill();
        skill.setTechnicianId(techId);
        skill.setEquipmentType(equipmentType);
        skill.setProficiency(proficiency);
        skill.setCertifiedFaultLevel(certLevel);
        return skill;
    }

    private Fault createFault(Long id, Long equipmentId, String equipmentType, int faultLevel) {
        Fault fault = new Fault();
        fault.setId(id);
        fault.setEquipmentId(equipmentId);
        fault.setEquipmentType(equipmentType);
        fault.setFaultLevel(faultLevel);
        fault.setFaultDescription("Test fault");
        fault.setStatus("REPORTED");
        return fault;
    }

    private WorkOrder createWorkOrder(Long id, String orderCode, Long faultId,
                                      Long equipmentId, int priority) {
        WorkOrder wo = new WorkOrder();
        wo.setId(id);
        wo.setOrderCode(orderCode);
        wo.setFaultId(faultId);
        wo.setEquipmentId(equipmentId);
        wo.setPriority(priority);
        wo.setStatus("CREATED");
        wo.setCreatedAt(LocalDateTime.of(2026, 6, 10, 8, 0, 0));
        return wo;
    }

    private Equipment createEquipment(Long id, String equipmentType, BigDecimal downtimeCostPerHour) {
        Equipment eq = new Equipment();
        eq.setId(id);
        eq.setEquipmentName("Test Equipment");
        eq.setEquipmentType(equipmentType);
        eq.setDowntimeCostPerHour(downtimeCostPerHour);
        eq.setStatus("RUNNING");
        return eq;
    }

    private SparePart createSparePart(Long id, String partCode, String partName,
                                      int stockQuantity) {
        SparePart part = new SparePart();
        part.setId(id);
        part.setPartCode(partCode);
        part.setPartName(partName);
        part.setStockQuantity(stockQuantity);
        part.setMinStock(5);
        part.setUnitPrice(BigDecimal.valueOf(100));
        return part;
    }

    private DispatchPlan createDispatchPlan(Long id, Long workOrderId, Long faultId,
                                            Long technicianId, String status) {
        DispatchPlan plan = new DispatchPlan();
        plan.setId(id);
        plan.setWorkOrderId(workOrderId);
        plan.setFaultId(faultId);
        plan.setTechnicianId(technicianId);
        plan.setPlanRank(1);
        plan.setTotalScore(BigDecimal.valueOf(85));
        plan.setStatus(status);
        plan.setCreatedAt(LocalDateTime.now());
        return plan;
    }

    /**
     * Sets up common stubs for generateDispatchPlans that are always needed
     * but may not be invoked in every path (e.g., when no techs pass hard filters).
     */
    private void stubCommonGenerateDefaults(Equipment equipment) {
        lenient().when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);
        lenient().when(sparePartMapper.selectByEquipmentType(equipment.getEquipmentType()))
                .thenReturn(Collections.emptyList());
        lenient().when(faultMapper.selectHistoryByEquipment(equipment.getId(), 20))
                .thenReturn(Collections.emptyList());
    }

    /**
     * Sets up stubs for a technician that should pass hard filters and scoring.
     * Uses lenient() because not all scoring sub-methods are called in every test path.
     */
    private void stubQualifiedTechnician(Long techId, String equipmentType,
                                          int proficiency, int certLevel,
                                          BigDecimal baseScore) {
        lenient().when(technicianSkillMapper.selectByTechnicianAndType(techId, equipmentType))
                .thenReturn(createSkill(techId, equipmentType, proficiency, certLevel));
        lenient().when(autoDispatchService.calculateScore(
                argThat(t -> t != null && t.getId().equals(techId)), any(), any(), any()))
                .thenReturn(baseScore);
        lenient().when(workOrderMapper.countByTechnicianAndStatus(techId, "COMPLETED"))
                .thenReturn(10);
        lenient().when(faultMapper.selectAvgRepairMinutes(equipmentType, techId))
                .thenReturn(null);
    }

    // ========================================================
    // TEST 1: Two qualified techs generate 2 ranked plans
    // ========================================================
    @Test
    @DisplayName("generateDispatchPlans: two qualified technicians produce 2 ranked plans")
    void generateDispatchPlans_multiPlan() {
        Technician tech1 = createTechnician(1L, "TechA", "EMP001",
                TechnicianAvailability.AVAILABLE.name(), 0);
        Technician tech2 = createTechnician(2L, "TechB", "EMP002",
                TechnicianAvailability.AVAILABLE.name(), 1);

        Fault fault = createFault(10L, 100L, "CNC", 2);
        WorkOrder workOrder = createWorkOrder(50L, "WO-MULTI", 10L, 100L, 2);
        Equipment equipment = createEquipment(100L, "CNC", BigDecimal.valueOf(500));

        stubCommonGenerateDefaults(equipment);
        when(technicianMapper.selectList(null)).thenReturn(Arrays.asList(tech1, tech2));

        // Tech1 gets higher base score than Tech2
        stubQualifiedTechnician(1L, "CNC", 4, 3, BigDecimal.valueOf(90));
        stubQualifiedTechnician(2L, "CNC", 3, 2, BigDecimal.valueOf(70));

        AtomicLong planIdSeq = new AtomicLong(1);
        doAnswer(inv -> {
            DispatchPlan p = inv.getArgument(0);
            p.setId(planIdSeq.getAndIncrement());
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlan.class));

        PredictiveDispatchResult result = predictiveDispatchService
                .generateDispatchPlans(workOrder, fault, equipment);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getPlans().size());
        // Plans should be ranked: rank 1 has the higher score
        assertEquals(1, result.getPlans().get(0).getRank());
        assertEquals(2, result.getPlans().get(1).getRank());
        assertEquals(1L, result.getPlans().get(0).getTechnicianId(),
                "Tech1 with higher score should be rank 1");
        assertEquals(2L, result.getPlans().get(1).getTechnicianId(),
                "Tech2 with lower score should be rank 2");
        verify(dispatchPlanMapper, times(2)).insert(any(DispatchPlan.class));
    }

    // ========================================================
    // TEST 2: No qualified techs returns fail result
    // ========================================================
    @Test
    @DisplayName("generateDispatchPlans: no qualified technicians returns failure result")
    void generateDispatchPlans_emptyPlans() {
        Technician tech = createTechnician(1L, "NoSkillTech", "EMP001",
                TechnicianAvailability.AVAILABLE.name(), 0);

        Fault fault = createFault(10L, 100L, "CNC", 2);
        WorkOrder workOrder = createWorkOrder(50L, "WO-EMPTY", 10L, 100L, 2);
        Equipment equipment = createEquipment(100L, "CNC", BigDecimal.valueOf(500));

        stubCommonGenerateDefaults(equipment);
        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(tech));
        // No matching skill -> tech is filtered out
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC")).thenReturn(null);

        PredictiveDispatchResult result = predictiveDispatchService
                .generateDispatchPlans(workOrder, fault, equipment);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("No qualified technician"));
        assertNull(result.getPlans());
        verify(dispatchPlanMapper, never()).insert(any(DispatchPlan.class));
    }

    // ========================================================
    // TEST 3: Emergency (faultLevel=3) auto-selects top plan
    // ========================================================
    @Test
    @DisplayName("generateDispatchPlans: emergency fault (level=3) auto-selects top plan and starts downtime")
    void generateDispatchPlans_emergencyAutoSelect() {
        Technician tech = createTechnician(1L, "EmergencyTech", "EMP001",
                TechnicianAvailability.AVAILABLE.name(), 0);

        Fault fault = createFault(10L, 100L, "CNC", 3);
        WorkOrder workOrder = createWorkOrder(50L, "WO-EMERG", 10L, 100L, 3);
        Equipment equipment = createEquipment(100L, "CNC", BigDecimal.valueOf(1000));

        stubCommonGenerateDefaults(equipment);
        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(tech));
        stubQualifiedTechnician(1L, "CNC", 4, 3, BigDecimal.valueOf(80));

        doAnswer(inv -> {
            DispatchPlan p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlan.class));

        // Stubs for selectAndExecutePlan called internally during emergency auto-select
        DispatchPlan storedPlan = createDispatchPlan(1L, 50L, 10L, 1L,
                DispatchPlanStatus.PENDING.name());
        when(dispatchPlanMapper.selectById(1L)).thenReturn(storedPlan);
        when(workOrderMapper.selectById(50L)).thenReturn(workOrder);
        when(faultMapper.selectById(10L)).thenReturn(fault);

        DispatchResult dispatchSuccess = DispatchResult.success(
                50L, "WO-EMERG", 1L, "EmergencyTech", BigDecimal.valueOf(80), "PREDICTIVE");
        when(autoDispatchService.executeFromPlan(any(), any(), any())).thenReturn(dispatchSuccess);

        PredictiveDispatchResult result = predictiveDispatchService
                .generateDispatchPlans(workOrder, fault, equipment);

        assertTrue(result.isSuccess());
        assertNotNull(result.getSelectedPlan(), "Emergency should auto-select top plan");
        assertNotNull(result.getDispatchResult(), "Emergency should have dispatch result");
        assertTrue(result.getDispatchResult().isSuccess());
        assertEquals(1L, result.getSelectedPlan().getTechnicianId());

        // Verify plan was marked SELECTED and others expired
        verify(dispatchPlanMapper).updateStatus(1L, DispatchPlanStatus.SELECTED.name());
        verify(dispatchPlanMapper).expirePendingPlans(50L);
        // Verify downtime was started because dispatch succeeded
        verify(downtimeService).startDowntime(100L, 50L, 10L);
    }

    // ========================================================
    // TEST 4: Parts are pre-reserved for top plan
    // ========================================================
    @Test
    @DisplayName("generateDispatchPlans: parts are pre-reserved when applicable parts exist")
    void generateDispatchPlans_partsPreReservation() {
        Technician tech = createTechnician(1L, "PartsTech", "EMP001",
                TechnicianAvailability.AVAILABLE.name(), 0);

        Fault fault = createFault(10L, 100L, "CNC", 2);
        WorkOrder workOrder = createWorkOrder(50L, "WO-PARTS", 10L, 100L, 2);
        Equipment equipment = createEquipment(100L, "CNC", BigDecimal.valueOf(500));

        // Setup parts with sufficient stock (stockQuantity=10 >= requiredQty=1 for faultLevel=2)
        SparePart part1 = createSparePart(1L, "P001", "Bearing", 10);
        when(sparePartMapper.selectByEquipmentType("CNC"))
                .thenReturn(Collections.singletonList(part1));

        lenient().when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);
        lenient().when(faultMapper.selectHistoryByEquipment(100L, 20))
                .thenReturn(Collections.emptyList());
        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(tech));
        stubQualifiedTechnician(1L, "CNC", 4, 3, BigDecimal.valueOf(80));

        doAnswer(inv -> {
            DispatchPlan p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlan.class));

        // Pre-reserve returns a non-empty list indicating success
        SparePartOccupation reservation = new SparePartOccupation();
        reservation.setId(1L);
        reservation.setWorkOrderId(50L);
        reservation.setPartId(1L);
        reservation.setQuantity(1);
        reservation.setStatus("PRE_RESERVED");
        when(sparePartService.preReserveParts(50L, "CNC", 2))
                .thenReturn(Collections.singletonList(reservation));

        PredictiveDispatchResult result = predictiveDispatchService
                .generateDispatchPlans(workOrder, fault, equipment);

        assertTrue(result.isSuccess());
        assertTrue(result.isPartsPreReserved(), "Parts should be pre-reserved");
        verify(sparePartService).preReserveParts(50L, "CNC", 2);
    }

    // ========================================================
    // TEST 5: Insufficient parts generates purchase suggestions
    //         and pauses SLA
    // ========================================================
    @Test
    @DisplayName("generateDispatchPlans: parts shortage triggers purchase suggestions and SLA pause")
    void generateDispatchPlans_partsShortage() {
        Technician tech = createTechnician(1L, "ShortageTech", "EMP001",
                TechnicianAvailability.AVAILABLE.name(), 0);

        Fault fault = createFault(10L, 100L, "CNC", 2);
        WorkOrder workOrder = createWorkOrder(50L, "WO-SHORT", 10L, 100L, 2);
        Equipment equipment = createEquipment(100L, "CNC", BigDecimal.valueOf(500));

        // Part with insufficient stock (stockQuantity=0, requiredQty=1 for faultLevel=2)
        SparePart shortPart = createSparePart(1L, "P001", "Bearing", 0);
        when(sparePartMapper.selectByEquipmentType("CNC"))
                .thenReturn(Collections.singletonList(shortPart));

        lenient().when(workOrderMapper.updateById(any(WorkOrder.class))).thenReturn(1);
        lenient().when(faultMapper.selectHistoryByEquipment(100L, 20))
                .thenReturn(Collections.emptyList());
        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(tech));
        stubQualifiedTechnician(1L, "CNC", 4, 3, BigDecimal.valueOf(80));

        doAnswer(inv -> {
            DispatchPlan p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlan.class));

        // preReserveParts returns empty (nothing to reserve since stock=0)
        lenient().when(sparePartService.preReserveParts(50L, "CNC", 2))
                .thenReturn(Collections.emptyList());

        // Purchase suggestions generated
        PurchaseSuggestionDTO suggestion = PurchaseSuggestionDTO.builder()
                .partId(1L)
                .partCode("P001")
                .partName("Bearing")
                .currentStock(0)
                .requiredQuantity(1)
                .suggestedPurchaseQuantity(5)
                .urgencyLevel("HIGH")
                .estimatedCost(BigDecimal.valueOf(500))
                .build();
        when(sparePartService.generatePurchaseSuggestions(50L, "CNC", 2))
                .thenReturn(Collections.singletonList(suggestion));

        PredictiveDispatchResult result = predictiveDispatchService
                .generateDispatchPlans(workOrder, fault, equipment);

        assertTrue(result.isSuccess());
        assertTrue(result.isSlaPaused(), "SLA should be paused when parts are insufficient");
        assertNotNull(result.getPurchaseSuggestions());
        assertFalse(result.getPurchaseSuggestions().isEmpty(),
                "Should have purchase suggestions");
        verify(workOrderService).pauseSla(workOrder);
    }

    // ========================================================
    // TEST 6: selectAndExecutePlan succeeds for PENDING plan
    // ========================================================
    @Test
    @DisplayName("selectAndExecutePlan: selects PENDING plan, promotes parts, and dispatches successfully")
    void selectAndExecutePlan_success() {
        DispatchPlan plan = createDispatchPlan(1L, 50L, 10L, 1L,
                DispatchPlanStatus.PENDING.name());
        WorkOrder workOrder = createWorkOrder(50L, "WO-SEL", 10L, 100L, 2);
        workOrder.setSlaPausedAt(LocalDateTime.of(2026, 6, 10, 9, 0, 0));
        Fault fault = createFault(10L, 100L, "CNC", 2);

        when(dispatchPlanMapper.selectById(1L)).thenReturn(plan);
        when(workOrderMapper.selectById(50L)).thenReturn(workOrder);
        when(faultMapper.selectById(10L)).thenReturn(fault);

        DispatchResult dispatchSuccess = DispatchResult.success(
                50L, "WO-SEL", 1L, "TechA", BigDecimal.valueOf(85), "PREDICTIVE");
        when(autoDispatchService.executeFromPlan(plan, workOrder, fault))
                .thenReturn(dispatchSuccess);

        DispatchResult result = predictiveDispatchService.selectAndExecutePlan(1L);

        assertTrue(result.isSuccess());
        assertEquals(1L, result.getTechnicianId());

        // Verify plan status updated to SELECTED
        verify(dispatchPlanMapper).updateStatus(1L, DispatchPlanStatus.SELECTED.name());
        // Verify other PENDING plans expired
        verify(dispatchPlanMapper).expirePendingPlans(50L);
        // Verify PRE_RESERVED parts promoted to OCCUPIED
        verify(sparePartService).promotePreReservationsToOccupied(50L);
        // Verify SLA resumed (slaPausedAt was set)
        verify(workOrderService).resumeSla(workOrder);
        // Verify DISPATCH_PLAN_SELECTED event published
        verify(messageQueue).publish(eq("DISPATCH_PLAN_SELECTED"), any());
    }

    // ========================================================
    // TEST 7: selectAndExecutePlan rejects non-PENDING plan
    // ========================================================
    @Test
    @DisplayName("selectAndExecutePlan: rejects plan that is not PENDING with BusinessException")
    void selectAndExecutePlan_notPending() {
        DispatchPlan plan = createDispatchPlan(1L, 50L, 10L, 1L,
                DispatchPlanStatus.SELECTED.name());
        when(dispatchPlanMapper.selectById(1L)).thenReturn(plan);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> predictiveDispatchService.selectAndExecutePlan(1L));

        assertTrue(ex.getMessage().contains("not PENDING"));
        verify(dispatchPlanMapper, never()).updateStatus(anyLong(), anyString());
        verify(autoDispatchService, never()).executeFromPlan(any(), any(), any());
    }

    // ========================================================
    // TEST 8: selectAndExecutePlan rejects nonexistent planId
    // ========================================================
    @Test
    @DisplayName("selectAndExecutePlan: rejects nonexistent planId with BusinessException")
    void selectAndExecutePlan_planNotFound() {
        when(dispatchPlanMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> predictiveDispatchService.selectAndExecutePlan(999L));

        assertTrue(ex.getMessage().contains("not found"));
        verify(dispatchPlanMapper, never()).updateStatus(anyLong(), anyString());
        verify(autoDispatchService, never()).executeFromPlan(any(), any(), any());
    }

    // ========================================================
    // TEST 9: OFFLINE technicians are filtered out
    // ========================================================
    @Test
    @DisplayName("generateDispatchPlans: OFFLINE technician is excluded, only AVAILABLE generates plan")
    void generateDispatchPlans_technicianOffline() {
        Technician offlineTech = createTechnician(1L, "OfflineTech", "EMP001",
                TechnicianAvailability.OFFLINE.name(), 0);
        Technician availableTech = createTechnician(2L, "AvailableTech", "EMP002",
                TechnicianAvailability.AVAILABLE.name(), 0);

        Fault fault = createFault(10L, 100L, "CNC", 2);
        WorkOrder workOrder = createWorkOrder(50L, "WO-OFFLINE", 10L, 100L, 2);
        Equipment equipment = createEquipment(100L, "CNC", BigDecimal.valueOf(500));

        stubCommonGenerateDefaults(equipment);
        when(technicianMapper.selectList(null)).thenReturn(Arrays.asList(offlineTech, availableTech));

        // Only the AVAILABLE tech should have skill looked up
        stubQualifiedTechnician(2L, "CNC", 4, 3, BigDecimal.valueOf(80));

        doAnswer(inv -> {
            DispatchPlan p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlan.class));

        PredictiveDispatchResult result = predictiveDispatchService
                .generateDispatchPlans(workOrder, fault, equipment);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getPlans().size());
        assertEquals(2L, result.getPlans().get(0).getTechnicianId(),
                "Only the AVAILABLE technician should generate a plan");
        // OFFLINE tech should never have skill lookup
        verify(technicianSkillMapper, never()).selectByTechnicianAndType(eq(1L), anyString());
    }

    // ========================================================
    // TEST 10: SLA deadline set correctly by fault level
    // ========================================================
    @Test
    @DisplayName("generateDispatchPlans: SLA deadline is set correctly based on fault level")
    void generateDispatchPlans_setSlaDeadline() {
        Technician tech = createTechnician(1L, "SlaTech", "EMP001",
                TechnicianAvailability.AVAILABLE.name(), 0);

        // faultLevel=2 -> calculateSlaMinutes returns 240 minutes (4 hours)
        Fault fault = createFault(10L, 100L, "CNC", 2);
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 10, 8, 0, 0);
        WorkOrder workOrder = createWorkOrder(50L, "WO-SLA", 10L, 100L, 2);
        workOrder.setCreatedAt(createdAt);
        Equipment equipment = createEquipment(100L, "CNC", BigDecimal.valueOf(500));

        stubCommonGenerateDefaults(equipment);
        when(technicianMapper.selectList(null)).thenReturn(Collections.singletonList(tech));
        stubQualifiedTechnician(1L, "CNC", 4, 3, BigDecimal.valueOf(80));

        doAnswer(inv -> {
            DispatchPlan p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlan.class));

        predictiveDispatchService.generateDispatchPlans(workOrder, fault, equipment);

        // faultLevel=2 -> calculateSlaMinutes returns 240 minutes
        LocalDateTime expectedDeadline = createdAt.plusMinutes(240);
        assertEquals(expectedDeadline, workOrder.getSlaDeadline(),
                "SLA deadline should be createdAt + 240 minutes for faultLevel=2");
        assertEquals(0, workOrder.getSlaPausedDurationMinutes(),
                "SLA paused duration should be initialized to 0");
        verify(workOrderMapper).updateById(workOrder);
    }
}
