package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.dto.DispatchPlanDTO;
import com.maintenance.dto.DispatchResult;
import com.maintenance.dto.PartAvailabilityDTO;
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
import com.maintenance.enums.EventType;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.enums.WorkOrderStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.DispatchPlanMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.TechnicianMapper;
import com.maintenance.mapper.TechnicianSkillMapper;
import com.maintenance.mapper.WorkOrderMapper;
import com.maintenance.websocket.MaintenanceWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PredictiveDispatchService {

    private static final int MAX_PLANS = 3;
    private static final BigDecimal MIN_SCORE_THRESHOLD = BigDecimal.valueOf(30);

    private final TechnicianMapper technicianMapper;
    private final TechnicianSkillMapper technicianSkillMapper;
    private final WorkOrderMapper workOrderMapper;
    private final DispatchPlanMapper dispatchPlanMapper;
    private final FaultMapper faultMapper;
    private final SparePartMapper sparePartMapper;
    private final SparePartService sparePartService;
    private final AutoDispatchService autoDispatchService;
    private final DowntimeService downtimeService;
    private final WorkOrderService workOrderService;
    private final AuditService auditService;
    private final LocalMessageQueue messageQueue;
    private final MaintenanceWebSocketHandler webSocketHandler;

    public PredictiveDispatchService(TechnicianMapper technicianMapper,
                                     TechnicianSkillMapper technicianSkillMapper,
                                     WorkOrderMapper workOrderMapper,
                                     DispatchPlanMapper dispatchPlanMapper,
                                     FaultMapper faultMapper,
                                     SparePartMapper sparePartMapper,
                                     SparePartService sparePartService,
                                     AutoDispatchService autoDispatchService,
                                     DowntimeService downtimeService,
                                     WorkOrderService workOrderService,
                                     AuditService auditService,
                                     LocalMessageQueue messageQueue,
                                     MaintenanceWebSocketHandler webSocketHandler) {
        this.technicianMapper = technicianMapper;
        this.technicianSkillMapper = technicianSkillMapper;
        this.workOrderMapper = workOrderMapper;
        this.dispatchPlanMapper = dispatchPlanMapper;
        this.faultMapper = faultMapper;
        this.sparePartMapper = sparePartMapper;
        this.sparePartService = sparePartService;
        this.autoDispatchService = autoDispatchService;
        this.downtimeService = downtimeService;
        this.workOrderService = workOrderService;
        this.auditService = auditService;
        this.messageQueue = messageQueue;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * Generate multiple ranked dispatch plans for a work order.
     * Scoring uses 8 dimensions: skill match, certification, availability, workload,
     * historical performance, parts availability, downtime cost impact, and SLA urgency.
     *
     * For emergency faults (faultLevel >= 3), the top plan is auto-selected and dispatched.
     */
    @Transactional
    public PredictiveDispatchResult generateDispatchPlans(WorkOrder workOrder, Fault fault, Equipment equipment) {
        // 1. Calculate SLA deadline and set on work order
        int slaMinutes = calculateSlaMinutes(fault.getFaultLevel());
        workOrder.setSlaDeadline(workOrder.getCreatedAt().plusMinutes(slaMinutes));
        workOrder.setSlaPausedDurationMinutes(0);
        workOrderMapper.updateById(workOrder);

        // 2. Fetch applicable spare parts for scoring
        List<SparePart> applicableParts = sparePartMapper.selectByEquipmentType(equipment.getEquipmentType());

        // 3. Fetch device historical faults for pattern analysis
        List<Fault> historicalFaults = faultMapper.selectHistoryByEquipment(equipment.getId(), 20);
        int historicalFaultCount = historicalFaults != null ? historicalFaults.size() : 0;

        // 4. Score all qualified technicians
        List<Technician> allTechnicians = technicianMapper.selectList(null);
        List<ScoredPlan> scoredPlans = new ArrayList<>();

        for (Technician tech : allTechnicians) {
            // Hard filters (same as AutoDispatchService)
            String availability = tech.getAvailability();
            if (TechnicianAvailability.OFFLINE.name().equals(availability)
                    || TechnicianAvailability.ON_LEAVE.name().equals(availability)) {
                continue;
            }

            TechnicianSkill skill = technicianSkillMapper.selectByTechnicianAndType(
                    tech.getId(), fault.getEquipmentType());
            if (skill == null) {
                continue;
            }

            if (skill.getCertifiedFaultLevel() != null
                    && fault.getFaultLevel() - skill.getCertifiedFaultLevel() > 1) {
                continue;
            }

            // Calculate extended 8-dimension score
            ScoreBreakdown breakdown = calculateExtendedScore(
                    tech, workOrder, fault, skill, equipment, applicableParts, slaMinutes, historicalFaultCount);

            if (breakdown.total.compareTo(MIN_SCORE_THRESHOLD) >= 0) {
                scoredPlans.add(new ScoredPlan(tech, skill, breakdown));
            }
        }

        // 5. Sort by total score descending, take top N
        scoredPlans.sort(Comparator.comparing((ScoredPlan sp) -> sp.breakdown.total).reversed());
        List<ScoredPlan> topPlans = scoredPlans.stream().limit(MAX_PLANS).toList();

        if (topPlans.isEmpty()) {
            log.warn("No qualified technician found for workOrder [{}]", workOrder.getId());
            return PredictiveDispatchResult.fail(
                    "No qualified technician available for equipment type=" + fault.getEquipmentType());
        }

        // 6. Persist dispatch plans and build DTOs
        List<DispatchPlanDTO> planDTOs = new ArrayList<>();
        for (int i = 0; i < topPlans.size(); i++) {
            ScoredPlan sp = topPlans.get(i);
            int rank = i + 1;
            int estimatedMinutes = estimateRepairTime(sp.tech.getId(), fault.getEquipmentType(), fault.getFaultLevel());
            BigDecimal estimatedLoss = equipment.getDowntimeCostPerHour() != null
                    ? equipment.getDowntimeCostPerHour()
                        .multiply(BigDecimal.valueOf(estimatedMinutes))
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            String reason = generateRecommendationReason(sp.tech, sp.skill, sp.breakdown, estimatedMinutes);

            DispatchPlan plan = new DispatchPlan();
            plan.setWorkOrderId(workOrder.getId());
            plan.setFaultId(fault.getId());
            plan.setTechnicianId(sp.tech.getId());
            plan.setPlanRank(rank);
            plan.setTotalScore(sp.breakdown.total);
            plan.setSkillScore(sp.breakdown.skill);
            plan.setCertScore(sp.breakdown.cert);
            plan.setAvailabilityScore(sp.breakdown.availability);
            plan.setWorkloadScore(sp.breakdown.workload);
            plan.setHistoryScore(sp.breakdown.history);
            plan.setPartsScore(sp.breakdown.parts);
            plan.setDowntimeCostScore(sp.breakdown.downtimeCost);
            plan.setSlaScore(sp.breakdown.sla);
            plan.setEstimatedRepairMinutes(estimatedMinutes);
            plan.setEstimatedDowntimeLoss(estimatedLoss);
            plan.setSlaRemainingMinutes(slaMinutes);
            plan.setRecommendationReason(reason);
            plan.setStatus(DispatchPlanStatus.PENDING.name());
            plan.setCreatedAt(LocalDateTime.now());
            dispatchPlanMapper.insert(plan);

            // Build parts availability for this plan
            List<PartAvailabilityDTO> partsAvail = buildPartsAvailability(applicableParts, fault.getFaultLevel());

            planDTOs.add(DispatchPlanDTO.builder()
                    .planId(plan.getId())
                    .rank(rank)
                    .technicianId(sp.tech.getId())
                    .technicianName(sp.tech.getName())
                    .totalScore(sp.breakdown.total)
                    .skillScore(sp.breakdown.skill)
                    .certScore(sp.breakdown.cert)
                    .availabilityScore(sp.breakdown.availability)
                    .workloadScore(sp.breakdown.workload)
                    .historyScore(sp.breakdown.history)
                    .partsScore(sp.breakdown.parts)
                    .downtimeCostScore(sp.breakdown.downtimeCost)
                    .slaScore(sp.breakdown.sla)
                    .estimatedRepairMinutes(estimatedMinutes)
                    .estimatedDowntimeLoss(estimatedLoss)
                    .slaRemainingMinutes(slaMinutes)
                    .recommendationReason(reason)
                    .partsAvailability(partsAvail)
                    .build());
        }

        // 7. Pre-reserve parts for top plan
        boolean partsPreReserved = false;
        List<SparePartOccupation> reservations = List.of();
        if (applicableParts != null && !applicableParts.isEmpty()) {
            reservations = sparePartService.preReserveParts(
                    workOrder.getId(), equipment.getEquipmentType(), fault.getFaultLevel());
            partsPreReserved = !reservations.isEmpty();
        }

        // 8. Generate purchase suggestions if parts are insufficient
        List<PurchaseSuggestionDTO> purchaseSuggestions = List.of();
        boolean slaPaused = false;
        if (applicableParts != null && !applicableParts.isEmpty()) {
            int requiredQty = estimateRequiredQuantity(fault.getFaultLevel());
            boolean allPartsSufficient = applicableParts.stream()
                    .allMatch(p -> p.getStockQuantity() >= requiredQty);
            if (!allPartsSufficient) {
                purchaseSuggestions = sparePartService.generatePurchaseSuggestions(
                        workOrder.getId(), equipment.getEquipmentType(), fault.getFaultLevel());
                if (!purchaseSuggestions.isEmpty()) {
                    workOrderService.pauseSla(workOrder);
                    slaPaused = true;
                }
            }
        }

        // 9. Publish DISPATCH_PLANS_GENERATED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrder.getId());
        eventPayload.put("orderCode", workOrder.getOrderCode());
        eventPayload.put("faultId", fault.getId());
        eventPayload.put("planCount", planDTOs.size());
        eventPayload.put("partsPreReserved", partsPreReserved);
        eventPayload.put("purchaseSuggestionCount", purchaseSuggestions.size());
        eventPayload.put("slaPaused", slaPaused);
        messageQueue.publish(EventType.DISPATCH_PLANS_GENERATED.name(), eventPayload);

        auditService.log("DISPATCH", "PLANS_GENERATED", "WorkOrder", workOrder.getId(), "SYSTEM",
                "Generated " + planDTOs.size() + " dispatch plans"
                        + ", partsPreReserved=" + partsPreReserved
                        + ", purchaseSuggestions=" + purchaseSuggestions.size());

        // 10. Build result
        PredictiveDispatchResult result = PredictiveDispatchResult.builder()
                .workOrderId(workOrder.getId())
                .orderCode(workOrder.getOrderCode())
                .plans(planDTOs)
                .partsPreReserved(partsPreReserved)
                .purchaseSuggestions(purchaseSuggestions)
                .slaPaused(slaPaused)
                .success(true)
                .message("派工方案生成成功, 共" + planDTOs.size() + "个方案")
                .build();

        // 11. Emergency auto-select: for faultLevel >= 3, auto-select and dispatch top plan
        if (fault.getFaultLevel() >= 3 && !planDTOs.isEmpty()) {
            log.info("Emergency fault (level={}), auto-selecting top plan for workOrder [{}]",
                    fault.getFaultLevel(), workOrder.getId());
            DispatchPlanDTO topPlan = planDTOs.get(0);
            DispatchResult dispatchResult = selectAndExecutePlan(topPlan.getPlanId());
            result.setSelectedPlan(topPlan);
            result.setDispatchResult(dispatchResult);

            if (dispatchResult.isSuccess()) {
                downtimeService.startDowntime(
                        workOrder.getEquipmentId(), workOrder.getId(), fault.getId());
            }
        }

        return result;
    }

    /**
     * Select a dispatch plan and execute the dispatch.
     * Marks the selected plan as SELECTED, expires other PENDING plans,
     * promotes PRE_RESERVED parts to OCCUPIED, and executes the dispatch.
     */
    @Transactional
    public DispatchResult selectAndExecutePlan(Long planId) {
        DispatchPlan plan = dispatchPlanMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException("Dispatch plan not found, planId=" + planId);
        }
        if (!DispatchPlanStatus.PENDING.name().equals(plan.getStatus())) {
            throw new BusinessException("Dispatch plan is not PENDING, current status=" + plan.getStatus());
        }

        WorkOrder workOrder = workOrderMapper.selectById(plan.getWorkOrderId());
        if (workOrder == null) {
            throw new BusinessException("Work order not found, workOrderId=" + plan.getWorkOrderId());
        }

        Fault fault = faultMapper.selectById(plan.getFaultId());
        if (fault == null) {
            throw new BusinessException("Fault not found, faultId=" + plan.getFaultId());
        }

        // 1. Mark selected plan as SELECTED
        dispatchPlanMapper.updateStatus(planId, DispatchPlanStatus.SELECTED.name());

        // 2. Expire other PENDING plans for this work order
        dispatchPlanMapper.expirePendingPlans(plan.getWorkOrderId());

        // 3. Promote PRE_RESERVED parts to OCCUPIED
        sparePartService.promotePreReservationsToOccupied(workOrder.getId());

        // 4. Resume SLA if it was paused
        if (workOrder.getSlaPausedAt() != null) {
            workOrderService.resumeSla(workOrder);
        }

        // 5. Execute the dispatch via AutoDispatchService
        DispatchResult dispatchResult = autoDispatchService.executeFromPlan(plan, workOrder, fault);

        // 6. Publish DISPATCH_PLAN_SELECTED event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrder.getId());
        eventPayload.put("orderCode", workOrder.getOrderCode());
        eventPayload.put("planId", planId);
        eventPayload.put("technicianId", plan.getTechnicianId());
        eventPayload.put("totalScore", plan.getTotalScore());
        messageQueue.publish(EventType.DISPATCH_PLAN_SELECTED.name(), eventPayload);

        auditService.log("DISPATCH", "PLAN_SELECTED", "WorkOrder", workOrder.getId(), "SYSTEM",
                "Plan selected: planId=" + planId
                        + ", technicianId=" + plan.getTechnicianId()
                        + ", score=" + plan.getTotalScore());

        return dispatchResult;
    }

    /**
     * Get dispatch plans for a fault.
     */
    public List<DispatchPlanDTO> getPlansByFault(Long faultId) {
        List<DispatchPlan> plans = dispatchPlanMapper.selectByFaultId(faultId);
        List<DispatchPlanDTO> dtos = new ArrayList<>();
        for (DispatchPlan plan : plans) {
            Technician tech = technicianMapper.selectById(plan.getTechnicianId());
            dtos.add(DispatchPlanDTO.builder()
                    .planId(plan.getId())
                    .rank(plan.getPlanRank())
                    .technicianId(plan.getTechnicianId())
                    .technicianName(tech != null ? tech.getName() : "Unknown")
                    .totalScore(plan.getTotalScore())
                    .skillScore(plan.getSkillScore())
                    .certScore(plan.getCertScore())
                    .availabilityScore(plan.getAvailabilityScore())
                    .workloadScore(plan.getWorkloadScore())
                    .historyScore(plan.getHistoryScore())
                    .partsScore(plan.getPartsScore())
                    .downtimeCostScore(plan.getDowntimeCostScore())
                    .slaScore(plan.getSlaScore())
                    .estimatedRepairMinutes(plan.getEstimatedRepairMinutes())
                    .estimatedDowntimeLoss(plan.getEstimatedDowntimeLoss())
                    .slaRemainingMinutes(plan.getSlaRemainingMinutes())
                    .recommendationReason(plan.getRecommendationReason())
                    .build());
        }
        return dtos;
    }

    // --- Private helpers ---

    /**
     * Extended 8-dimension scoring algorithm.
     * Original 5 dimensions (0-125): skill(0-30), cert(0-20), availability(0-25), workload(0-25), history(0-25)
     * New 3 dimensions: parts(0-20), downtimeCost(0-20), sla(0-15)
     * Total possible: 0-180
     */
    private ScoreBreakdown calculateExtendedScore(Technician tech, WorkOrder order, Fault fault,
                                                   TechnicianSkill skill, Equipment equipment,
                                                   List<SparePart> applicableParts,
                                                   int slaMinutes, int historicalFaultCount) {
        // Reuse base scoring from AutoDispatchService (5 dimensions)
        BigDecimal baseScore = autoDispatchService.calculateScore(tech, order, fault, skill);

        // Decompose base score into sub-scores for breakdown reporting
        BigDecimal skillScore = calculateSkillSubScore(skill);
        BigDecimal certScore = calculateCertSubScore(skill, fault.getFaultLevel());
        BigDecimal availScore = calculateAvailSubScore(tech);
        BigDecimal workloadScore = calculateWorkloadSubScore(tech);
        BigDecimal historyScore = calculateHistorySubScore(tech);

        // New dimension 6: Parts availability (0-20)
        BigDecimal partsScore = calculatePartsScore(applicableParts, fault.getFaultLevel());

        // New dimension 7: Downtime cost impact (0-20)
        int estimatedMinutes = estimateRepairTime(tech.getId(), fault.getEquipmentType(), fault.getFaultLevel());
        BigDecimal downtimeCostScore = calculateDowntimeCostScore(equipment, estimatedMinutes);

        // New dimension 8: SLA urgency (0-15)
        BigDecimal slaScore = calculateSlaScore(slaMinutes, estimatedMinutes);

        BigDecimal totalScore = baseScore.add(partsScore).add(downtimeCostScore).add(slaScore)
                .setScale(2, RoundingMode.HALF_UP);

        return new ScoreBreakdown(totalScore, skillScore, certScore, availScore,
                workloadScore, historyScore, partsScore, downtimeCostScore, slaScore);
    }

    private BigDecimal calculateSkillSubScore(TechnicianSkill skill) {
        if (skill == null) return BigDecimal.ZERO;
        BigDecimal score = BigDecimal.valueOf(20);
        int bonus = Math.min(skill.getProficiency() * 2, 10);
        return score.add(BigDecimal.valueOf(bonus));
    }

    private BigDecimal calculateCertSubScore(TechnicianSkill skill, int faultLevel) {
        if (skill == null || skill.getCertifiedFaultLevel() == null) return BigDecimal.ZERO;
        int certLevel = skill.getCertifiedFaultLevel();
        if (certLevel >= faultLevel) return BigDecimal.valueOf(20);
        int deficit = faultLevel - certLevel;
        return BigDecimal.valueOf(Math.max(20 - deficit * 10, 0));
    }

    private BigDecimal calculateAvailSubScore(Technician tech) {
        if (TechnicianAvailability.AVAILABLE.name().equals(tech.getAvailability())) return BigDecimal.valueOf(25);
        if (TechnicianAvailability.BUSY.name().equals(tech.getAvailability())) return BigDecimal.valueOf(10);
        return BigDecimal.ZERO;
    }

    private BigDecimal calculateWorkloadSubScore(Technician tech) {
        int workload = tech.getCurrentWorkload() != null ? tech.getCurrentWorkload() : 0;
        return BigDecimal.valueOf(Math.max(25 - workload * 5, 0));
    }

    private BigDecimal calculateHistorySubScore(Technician tech) {
        int completedCount = workOrderMapper.countByTechnicianAndStatus(
                tech.getId(), WorkOrderStatus.COMPLETED.name());
        if (completedCount == 0) return BigDecimal.valueOf(15);
        if (completedCount >= 20) return BigDecimal.valueOf(25);
        if (completedCount >= 11) return BigDecimal.valueOf(20);
        if (completedCount >= 6) return BigDecimal.valueOf(15);
        return BigDecimal.valueOf(10);
    }

    /**
     * Parts availability score (0-20): higher if all parts are in stock.
     */
    private BigDecimal calculatePartsScore(List<SparePart> applicableParts, int faultLevel) {
        if (applicableParts == null || applicableParts.isEmpty()) {
            return BigDecimal.valueOf(20); // No parts needed = full score
        }
        int requiredQty = estimateRequiredQuantity(faultLevel);
        long sufficientCount = applicableParts.stream()
                .filter(p -> p.getStockQuantity() >= requiredQty)
                .count();
        double ratio = (double) sufficientCount / applicableParts.size();
        return BigDecimal.valueOf(ratio * 20).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Downtime cost impact score (0-20): lower estimated downtime = higher score.
     */
    private BigDecimal calculateDowntimeCostScore(Equipment equipment, int estimatedMinutes) {
        if (equipment.getDowntimeCostPerHour() == null
                || equipment.getDowntimeCostPerHour().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(10); // Unknown cost, moderate score
        }
        // Faster repair (less downtime) = higher score
        // Normalize: <=60min=20, 120min=15, 240min=10, 480min=5, >480min=0
        if (estimatedMinutes <= 60) return BigDecimal.valueOf(20);
        if (estimatedMinutes <= 120) return BigDecimal.valueOf(15);
        if (estimatedMinutes <= 240) return BigDecimal.valueOf(10);
        if (estimatedMinutes <= 480) return BigDecimal.valueOf(5);
        return BigDecimal.ZERO;
    }

    /**
     * SLA urgency score (0-15): tighter deadline = higher urgency bonus for faster technicians.
     */
    private BigDecimal calculateSlaScore(int slaMinutes, int estimatedMinutes) {
        if (slaMinutes <= 0) return BigDecimal.ZERO;
        double ratio = (double) estimatedMinutes / slaMinutes;
        // If repair takes < 50% of SLA time, full score
        if (ratio <= 0.5) return BigDecimal.valueOf(15);
        // 50%-75%: moderate score
        if (ratio <= 0.75) return BigDecimal.valueOf(10);
        // 75%-100%: low score
        if (ratio <= 1.0) return BigDecimal.valueOf(5);
        // Would exceed SLA
        return BigDecimal.ZERO;
    }

    /**
     * Estimate repair time in minutes for a technician + equipment type combination.
     */
    int estimateRepairTime(Long technicianId, String equipmentType, int faultLevel) {
        Integer avgMinutes = faultMapper.selectAvgRepairMinutes(equipmentType, technicianId);
        if (avgMinutes != null && avgMinutes > 0) {
            return avgMinutes;
        }
        // Default estimates by fault level
        return switch (faultLevel) {
            case 1 -> 60;
            case 2 -> 120;
            case 3 -> 240;
            case 4 -> 480;
            default -> 120;
        };
    }

    /**
     * Calculate SLA deadline in minutes based on fault level.
     */
    int calculateSlaMinutes(int faultLevel) {
        return switch (faultLevel) {
            case 1 -> 480;  // 8 hours
            case 2 -> 240;  // 4 hours
            case 3 -> 120;  // 2 hours
            case 4 -> 60;   // 1 hour
            default -> 240;
        };
    }

    private int estimateRequiredQuantity(int faultLevel) {
        return switch (faultLevel) {
            case 4 -> 3;
            case 3 -> 2;
            default -> 1;
        };
    }

    /**
     * Generate a human-readable recommendation reason.
     */
    private String generateRecommendationReason(Technician tech, TechnicianSkill skill,
                                                 ScoreBreakdown breakdown, int estimatedMinutes) {
        StringBuilder sb = new StringBuilder();
        sb.append("推荐维修工").append(tech.getName()).append("(工号:").append(tech.getEmployeeCode()).append(")");

        // Highlight top strengths
        if (breakdown.skill.compareTo(BigDecimal.valueOf(25)) >= 0) {
            sb.append(", 技能匹配度高(熟练度").append(skill.getProficiency()).append("级)");
        }
        if (breakdown.availability.compareTo(BigDecimal.valueOf(20)) >= 0) {
            sb.append(", 当前可用");
        }
        if (breakdown.workload.compareTo(BigDecimal.valueOf(20)) >= 0) {
            sb.append(", 工作负载低(").append(tech.getCurrentWorkload()).append("个在进行任务)");
        }
        if (breakdown.parts.compareTo(BigDecimal.valueOf(15)) >= 0) {
            sb.append(", 所需备件充足");
        }
        if (breakdown.sla.compareTo(BigDecimal.valueOf(10)) >= 0) {
            sb.append(", 预计").append(estimatedMinutes).append("分钟内完成维修");
        }

        sb.append("。综合评分: ").append(breakdown.total).append("分");
        return sb.toString();
    }

    private List<PartAvailabilityDTO> buildPartsAvailability(List<SparePart> parts, int faultLevel) {
        if (parts == null || parts.isEmpty()) return List.of();
        int requiredQty = estimateRequiredQuantity(faultLevel);
        return parts.stream()
                .map(p -> PartAvailabilityDTO.builder()
                        .partId(p.getId())
                        .partCode(p.getPartCode())
                        .partName(p.getPartName())
                        .currentStock(p.getStockQuantity())
                        .requiredQuantity(requiredQty)
                        .sufficient(p.getStockQuantity() >= requiredQty)
                        .build())
                .toList();
    }

    // --- Internal records ---

    private record ScoreBreakdown(BigDecimal total, BigDecimal skill, BigDecimal cert,
                                   BigDecimal availability, BigDecimal workload, BigDecimal history,
                                   BigDecimal parts, BigDecimal downtimeCost, BigDecimal sla) {}

    private record ScoredPlan(Technician tech, TechnicianSkill skill, ScoreBreakdown breakdown) {}
}
