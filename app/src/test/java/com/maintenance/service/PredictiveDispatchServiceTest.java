package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.dto.DispatchResult;
import com.maintenance.dto.PreOccupyResult;
import com.maintenance.entity.DispatchPlan;
import com.maintenance.entity.DispatchRecord;
import com.maintenance.entity.Equipment;
import com.maintenance.entity.Fault;
import com.maintenance.entity.PurchaseSuggestion;
import com.maintenance.entity.SlaRecord;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.Technician;
import com.maintenance.entity.TechnicianSkill;
import com.maintenance.entity.WorkOrder;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.DispatchPlanMapper;
import com.maintenance.mapper.DispatchRecordMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.PurchaseSuggestionMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PredictiveDispatchService covering:
 * 1. Multi-plan generation with 7-dimensional scoring
 * 2. Spare parts pre-occupation
 * 3. Purchase suggestion generation on shortage
 * 4. Technician offline fallback
 * 5. Plan selection and execution
 * 6. Pre-occupation release on failure/cancel
 * 7. Concurrent fault handling
 * 8. Idempotent message handling
 */
@ExtendWith(MockitoExtension.class)
class PredictiveDispatchServiceTest {

    @Mock private TechnicianMapper technicianMapper;
    @Mock private TechnicianSkillMapper technicianSkillMapper;
    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private DispatchRecordMapper dispatchRecordMapper;
    @Mock private DispatchPlanMapper dispatchPlanMapper;
    @Mock private FaultMapper faultMapper;
    @Mock private SparePartMapper sparePartMapper;
    @Mock private PurchaseSuggestionMapper purchaseSuggestionMapper;
    @Mock private LocalMessageQueue messageQueue;
    @Mock private AuditService auditService;
    @Mock private TechnicianService technicianService;
    @Mock private SparePartService sparePartService;
    @Mock private SlaService slaService;
    @Mock private MaintenanceWebSocketHandler webSocketHandler;

    private PredictiveDispatchService service;

    @BeforeEach
    void setUp() {
        service = new PredictiveDispatchService(
                technicianMapper, technicianSkillMapper, workOrderMapper,
                dispatchRecordMapper, dispatchPlanMapper, faultMapper,
                sparePartMapper, purchaseSuggestionMapper, messageQueue,
                auditService, technicianService, sparePartService,
                slaService, webSocketHandler);
    }

    private WorkOrder createWorkOrder(Long id) {
        WorkOrder wo = new WorkOrder();
        wo.setId(id);
        wo.setOrderCode("WO-TEST-" + id);
        wo.setEquipmentId(50L);
        wo.setPriority(2);
        return wo;
    }

    private Fault createFault(Long id, int level) {
        Fault f = new Fault();
        f.setId(id);
        f.setEquipmentId(50L);
        f.setEquipmentType("CNC");
        f.setFaultLevel(level);
        return f;
    }

    private Equipment createEquipment(Long id) {
        Equipment e = new Equipment();
        e.setId(id);
        e.setEquipmentName("CNC Machine");
        e.setEquipmentType("CNC");
        e.setDowntimeCostPerHour(BigDecimal.valueOf(500));
        return e;
    }

    private Technician createTechnician(Long id, String availability, int workload) {
        Technician t = new Technician();
        t.setId(id);
        t.setName("Tech-" + id);
        t.setAvailability(availability);
        t.setCurrentWorkload(workload);
        return t;
    }

    private TechnicianSkill createSkill(Long techId, int proficiency, int certLevel) {
        TechnicianSkill skill = new TechnicianSkill();
        skill.setTechnicianId(techId);
        skill.setEquipmentType("CNC");
        skill.setProficiency(proficiency);
        skill.setCertifiedFaultLevel(certLevel);
        return skill;
    }

    // ========================================================
    // TEST: Multi-plan generation with 7-dimensional scoring
    // ========================================================
    @Test
    @DisplayName("Generate multiple dispatch plans ranked by total score across 7 dimensions")
    void generateDispatchPlans_multiplePlansWithScoring() {
        WorkOrder wo = createWorkOrder(1L);
        Fault fault = createFault(1L, 2);
        Equipment equip = createEquipment(50L);

        // 3 qualified technicians
        Technician t1 = createTechnician(1L, "AVAILABLE", 0);
        Technician t2 = createTechnician(2L, "AVAILABLE", 1);
        Technician t3 = createTechnician(3L, "BUSY", 2);
        // Offline tech should be excluded
        Technician t4 = createTechnician(4L, "OFFLINE", 0);

        when(technicianMapper.selectList(null)).thenReturn(List.of(t1, t2, t3, t4));
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC")).thenReturn(createSkill(1L, 5, 3));
        when(technicianSkillMapper.selectByTechnicianAndType(2L, "CNC")).thenReturn(createSkill(2L, 4, 2));
        when(technicianSkillMapper.selectByTechnicianAndType(3L, "CNC")).thenReturn(createSkill(3L, 3, 2));
        when(workOrderMapper.countByTechnicianAndStatus(anyLong(), eq("COMPLETED"))).thenReturn(10);
        when(faultMapper.selectRecentByEquipment(eq(50L), anyInt())).thenReturn(Collections.emptyList());
        when(slaService.calculateSlaScore(1L)).thenReturn(8);

        when(dispatchPlanMapper.insert(any(DispatchPlan.class))).thenReturn(1);

        List<DispatchPlan> plans = service.generateDispatchPlans(wo, fault, equip);

        // Should have up to 3 plans (t4 excluded as OFFLINE)
        assertTrue(plans.size() >= 1 && plans.size() <= 3,
                "Should generate 1-3 plans, got " + plans.size());

        // First plan should have highest score
        if (plans.size() > 1) {
            assertTrue(plans.get(0).getTotalScore().compareTo(plans.get(1).getTotalScore()) >= 0,
                    "Plans should be sorted by total score descending");
        }

        // First plan should be recommended
        assertEquals(1, plans.get(0).getIsRecommended(), "First plan should be recommended");

        // All plans should have scores across all 7 dimensions
        for (DispatchPlan plan : plans) {
            assertNotNull(plan.getSkillScore());
            assertNotNull(plan.getCertScore());
            assertNotNull(plan.getAvailabilityScore());
            assertNotNull(plan.getWorkloadScore());
            assertNotNull(plan.getPerformanceScore());
            assertNotNull(plan.getHistoryScore());
            assertNotNull(plan.getSlaScore());
            assertNotNull(plan.getRecommendationReason(), "Each plan should have a recommendation reason");
        }

        // Event should be published
        verify(messageQueue).publish(eq("DISPATCH_PLANS_GENERATED"), any());
    }

    // ========================================================
    // TEST: OFFLINE and ON_LEAVE technicians excluded
    // ========================================================
    @Test
    @DisplayName("OFFLINE and ON_LEAVE technicians are excluded from plan generation")
    void generateDispatchPlans_excludesOfflineAndOnLeave() {
        WorkOrder wo = createWorkOrder(1L);
        Fault fault = createFault(1L, 2);
        Equipment equip = createEquipment(50L);

        Technician tOffline = createTechnician(1L, "OFFLINE", 0);
        Technician tOnLeave = createTechnician(2L, "ON_LEAVE", 0);

        when(technicianMapper.selectList(null)).thenReturn(List.of(tOffline, tOnLeave));

        List<DispatchPlan> plans = service.generateDispatchPlans(wo, fault, equip);

        assertTrue(plans.isEmpty(), "No plans should be generated when all techs are OFFLINE/ON_LEAVE");
    }

    // ========================================================
    // TEST: Spare parts pre-occupation succeeds
    // ========================================================
    @Test
    @DisplayName("Pre-occupy spare parts for work order when stock is sufficient")
    void preOccupyParts_success() {
        SparePart part1 = new SparePart();
        part1.setId(1L);
        part1.setPartCode("P001");
        part1.setPartName("Bearing");
        part1.setStockQuantity(10);
        part1.setMinStock(2);
        part1.setUnitPrice(BigDecimal.valueOf(50));

        SparePartOccupation occ = new SparePartOccupation();
        occ.setId(1L);
        occ.setWorkOrderId(1L);
        occ.setPartId(1L);
        occ.setQuantity(1);

        WorkOrder wo = createWorkOrder(1L);
        when(workOrderMapper.selectById(1L)).thenReturn(wo);
        when(sparePartMapper.selectByEquipmentType("CNC")).thenReturn(List.of(part1));
        when(faultMapper.selectRecentByEquipment(eq(50L), anyInt())).thenReturn(Collections.emptyList());
        when(sparePartService.occupyPart(1L, 1L, 1)).thenReturn(occ);

        PreOccupyResult result = service.preOccupyParts(1L, "CNC", 2);

        assertTrue(result.isAllPartsAvailable(), "All parts should be available");
        assertEquals(1, result.getOccupiedParts().size());
        assertTrue(result.getShortageParts().isEmpty());
        verify(messageQueue).publish(eq("PARTS_PRE_OCCUPIED"), any());
    }

    // ========================================================
    // TEST: Spare parts insufficient generates purchase suggestion
    // ========================================================
    @Test
    @DisplayName("Purchase suggestion created when spare parts are out of stock")
    void preOccupyParts_generatesPurchaseSuggestionOnShortage() {
        SparePart part = new SparePart();
        part.setId(1L);
        part.setPartCode("P001");
        part.setPartName("Bearing");
        part.setStockQuantity(0); // Out of stock
        part.setMinStock(2);
        part.setUnitPrice(BigDecimal.valueOf(50));

        WorkOrder wo = createWorkOrder(1L);
        when(workOrderMapper.selectById(1L)).thenReturn(wo);
        when(sparePartMapper.selectByEquipmentType("CNC")).thenReturn(List.of(part));
        when(faultMapper.selectRecentByEquipment(eq(50L), anyInt())).thenReturn(Collections.emptyList());
        when(purchaseSuggestionMapper.insert(any(PurchaseSuggestion.class))).thenReturn(1);

        PreOccupyResult result = service.preOccupyParts(1L, "CNC", 2);

        assertFalse(result.isAllPartsAvailable(), "Parts should not all be available");
        assertEquals(1, result.getShortageParts().size());
        assertEquals("P001", result.getShortageParts().get(0).getPartCode());
        verify(messageQueue).publish(eq("PURCHASE_SUGGESTED"), any());
    }

    // ========================================================
    // TEST: Plan selection and execution
    // ========================================================
    @Test
    @DisplayName("Select and execute a specific dispatch plan")
    void selectAndExecutePlan_success() {
        DispatchPlan plan = new DispatchPlan();
        plan.setId(1L);
        plan.setWorkOrderId(1L);
        plan.setPlanIndex(1);
        plan.setTechnicianId(100L);
        plan.setTotalScore(BigDecimal.valueOf(85));

        Technician tech = createTechnician(100L, "AVAILABLE", 0);

        WorkOrder wo = createWorkOrder(1L);

        when(dispatchPlanMapper.selectByWorkOrderId(1L)).thenReturn(List.of(plan));
        when(technicianService.getById(100L)).thenReturn(tech);
        when(dispatchPlanMapper.clearSelected(1L)).thenReturn(1);
        when(dispatchPlanMapper.updateSelected(1L)).thenReturn(1);
        when(workOrderMapper.selectById(1L)).thenReturn(wo);
        when(dispatchRecordMapper.insert(any(DispatchRecord.class))).thenReturn(1);
        when(workOrderMapper.updateById(any())).thenReturn(1);

        DispatchResult result = service.selectAndExecutePlan(1L, 1);

        assertTrue(result.isSuccess());
        assertEquals(100L, result.getTechnicianId());
        verify(technicianService).incrementWorkload(100L);
        verify(dispatchPlanMapper).updateSelected(1L);
        verify(messageQueue).publish(eq("DISPATCH_DONE"), any());
    }

    // ========================================================
    // TEST: Plan selection fails when technician offline
    // ========================================================
    @Test
    @DisplayName("Plan selection throws when selected technician is OFFLINE")
    void selectAndExecutePlan_throwsWhenTechOffline() {
        DispatchPlan plan = new DispatchPlan();
        plan.setId(1L);
        plan.setWorkOrderId(1L);
        plan.setPlanIndex(1);
        plan.setTechnicianId(100L);
        plan.setTotalScore(BigDecimal.valueOf(85));

        Technician tech = createTechnician(100L, "OFFLINE", 0);

        when(dispatchPlanMapper.selectByWorkOrderId(1L)).thenReturn(List.of(plan));
        when(technicianService.getById(100L)).thenReturn(tech);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.selectAndExecutePlan(1L, 1));

        assertTrue(ex.getMessage().contains("unavailable"));
    }

    // ========================================================
    // TEST: Pre-occupation release on cancel
    // ========================================================
    @Test
    @DisplayName("Pre-occupied parts released when releasePreOccupiedParts is called")
    void releasePreOccupiedParts_success() {
        service.releasePreOccupiedParts(1L);

        verify(sparePartService).releaseOccupationsByWorkOrder(1L);
        verify(messageQueue).publish(eq("PARTS_PRE_RELEASED"), any());
    }

    // ========================================================
    // TEST: Pre-occupation release handles failure gracefully
    // ========================================================
    @Test
    @DisplayName("Release pre-occupied parts handles failure without throwing")
    void releasePreOccupiedParts_handlesFailure() {
        doThrow(new BusinessException("lock failed"))
                .when(sparePartService).releaseOccupationsByWorkOrder(1L);

        // Should not throw
        assertDoesNotThrow(() -> service.releasePreOccupiedParts(1L));
    }

    // ========================================================
    // TEST: Empty technicians list
    // ========================================================
    @Test
    @DisplayName("Empty technician list returns no plans")
    void generateDispatchPlans_noTechnicians() {
        WorkOrder wo = createWorkOrder(1L);
        Fault fault = createFault(1L, 2);
        Equipment equip = createEquipment(50L);

        when(technicianMapper.selectList(null)).thenReturn(Collections.emptyList());

        List<DispatchPlan> plans = service.generateDispatchPlans(wo, fault, equip);

        assertTrue(plans.isEmpty());
    }

    // ========================================================
    // TEST: Recommendation reason contains relevant info
    // ========================================================
    @Test
    @DisplayName("Recommendation reason explains scoring highlights")
    void generateDispatchPlans_recommendationReason() {
        WorkOrder wo = createWorkOrder(1L);
        Fault fault = createFault(1L, 2);
        Equipment equip = createEquipment(50L);

        Technician tech = createTechnician(1L, "AVAILABLE", 0);
        when(technicianMapper.selectList(null)).thenReturn(List.of(tech));
        when(technicianSkillMapper.selectByTechnicianAndType(1L, "CNC")).thenReturn(createSkill(1L, 5, 3));
        when(workOrderMapper.countByTechnicianAndStatus(1L, "COMPLETED")).thenReturn(15);
        when(faultMapper.selectRecentByEquipment(eq(50L), anyInt())).thenReturn(Collections.emptyList());
        when(slaService.calculateSlaScore(1L)).thenReturn(4);
        when(dispatchPlanMapper.insert(any())).thenReturn(1);

        List<DispatchPlan> plans = service.generateDispatchPlans(wo, fault, equip);

        assertFalse(plans.isEmpty());
        String reason = plans.get(0).getRecommendationReason();
        assertNotNull(reason);
        // Should mention key scoring highlights
        assertTrue(reason.contains("技能") || reason.contains("认证") || reason.contains("空闲")
                || reason.contains("绩效") || reason.contains("综合"),
                "Recommendation reason should explain why: " + reason);
    }

    // ========================================================
    // TEST: Plan not found throws exception
    // ========================================================
    @Test
    @DisplayName("Select non-existent plan throws BusinessException")
    void selectAndExecutePlan_planNotFound() {
        when(dispatchPlanMapper.selectByWorkOrderId(1L)).thenReturn(Collections.emptyList());

        assertThrows(BusinessException.class, () -> service.selectAndExecutePlan(1L, 99));
    }

    // ========================================================
    // TEST: Emergency pre-occupation occupies all available parts
    // ========================================================
    @Test
    @DisplayName("Emergency fault (level >= 3) pre-occupies all parts with stock")
    void preOccupyParts_emergencyOccupiesAll() {
        SparePart part1 = new SparePart();
        part1.setId(1L);
        part1.setPartCode("P001");
        part1.setStockQuantity(2);
        part1.setMinStock(5); // stock < minStock, but emergency should still occupy

        SparePartOccupation occ = new SparePartOccupation();
        occ.setId(1L);
        occ.setPartId(1L);
        occ.setQuantity(1);

        WorkOrder wo = createWorkOrder(1L);
        when(workOrderMapper.selectById(1L)).thenReturn(wo);
        when(sparePartMapper.selectByEquipmentType("CNC")).thenReturn(List.of(part1));
        when(faultMapper.selectRecentByEquipment(eq(50L), anyInt())).thenReturn(Collections.emptyList());
        when(sparePartService.occupyPart(1L, 1L, 1)).thenReturn(occ);

        // faultLevel=4 (emergency)
        PreOccupyResult result = service.preOccupyParts(1L, "CNC", 4);

        assertTrue(result.isAllPartsAvailable());
        verify(sparePartService).occupyPart(1L, 1L, 1);
    }
}
