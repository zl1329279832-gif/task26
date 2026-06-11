package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.dto.DispatchPlanResult;
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
import com.maintenance.enums.DispatchType;
import com.maintenance.enums.EventType;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.enums.WorkOrderStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.infrastructure.queue.TransactionAwareEventPublisher;
import com.maintenance.mapper.DispatchPlanMapper;
import com.maintenance.mapper.DispatchRecordMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.PurchaseSuggestionMapper;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.TechnicianMapper;
import com.maintenance.mapper.TechnicianSkillMapper;
import com.maintenance.mapper.WorkOrderMapper;
import com.maintenance.websocket.MaintenanceWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
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

    private final TechnicianMapper technicianMapper;
    private final TechnicianSkillMapper technicianSkillMapper;
    private final WorkOrderMapper workOrderMapper;
    private final DispatchRecordMapper dispatchRecordMapper;
    private final DispatchPlanMapper dispatchPlanMapper;
    private final FaultMapper faultMapper;
    private final SparePartMapper sparePartMapper;
    private final PurchaseSuggestionMapper purchaseSuggestionMapper;
    private final LocalMessageQueue messageQueue;
    private final TransactionAwareEventPublisher txPublisher;
    private final AuditService auditService;
    private final TechnicianService technicianService;
    private final SparePartService sparePartService;
    private final SlaService slaService;
    private final MaintenanceWebSocketHandler webSocketHandler;

    public PredictiveDispatchService(TechnicianMapper technicianMapper,
                                     TechnicianSkillMapper technicianSkillMapper,
                                     WorkOrderMapper workOrderMapper,
                                     DispatchRecordMapper dispatchRecordMapper,
                                     DispatchPlanMapper dispatchPlanMapper,
                                     FaultMapper faultMapper,
                                     SparePartMapper sparePartMapper,
                                     PurchaseSuggestionMapper purchaseSuggestionMapper,
                                     LocalMessageQueue messageQueue,
                                     TransactionAwareEventPublisher txPublisher,
                                     AuditService auditService,
                                     TechnicianService technicianService,
                                     @Lazy SparePartService sparePartService,
                                     SlaService slaService,
                                     MaintenanceWebSocketHandler webSocketHandler) {
        this.technicianMapper = technicianMapper;
        this.technicianSkillMapper = technicianSkillMapper;
        this.workOrderMapper = workOrderMapper;
        this.dispatchRecordMapper = dispatchRecordMapper;
        this.dispatchPlanMapper = dispatchPlanMapper;
        this.faultMapper = faultMapper;
        this.sparePartMapper = sparePartMapper;
        this.purchaseSuggestionMapper = purchaseSuggestionMapper;
        this.messageQueue = messageQueue;
        this.txPublisher = txPublisher;
        this.auditService = auditService;
        this.technicianService = technicianService;
        this.sparePartService = sparePartService;
        this.slaService = slaService;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * Generate multiple dispatch plans with multi-dimensional scoring.
     * Returns top candidates ranked by total score with recommendation reasons.
     */
    public List<DispatchPlan> generateDispatchPlans(WorkOrder workOrder, Fault fault, Equipment equipment) {
        List<Technician> allTechnicians = technicianMapper.selectList(null);
        if (allTechnicians == null || allTechnicians.isEmpty()) {
            log.warn("No technicians available for dispatch plan generation");
            return List.of();
        }

        // Calculate equipment fault history score (shared across all plans)
        int historyScore = calculateHistoryScore(equipment.getId());

        // Calculate SLA score
        int slaScore = slaService.calculateSlaScore(workOrder.getId());

        // Estimated downtime hours by fault level
        double estimatedHours = estimateRepairHours(fault.getFaultLevel());
        BigDecimal estimatedLoss = BigDecimal.ZERO;
        if (equipment.getDowntimeCostPerHour() != null) {
            estimatedLoss = equipment.getDowntimeCostPerHour()
                    .multiply(BigDecimal.valueOf(estimatedHours))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // Score all qualified technicians
        List<ScoredCandidate> candidates = new ArrayList<>();
        for (Technician tech : allTechnicians) {
            // HARD FILTER: exclude OFFLINE and ON_LEAVE
            String availability = tech.getAvailability();
            if (TechnicianAvailability.OFFLINE.name().equals(availability)
                    || TechnicianAvailability.ON_LEAVE.name().equals(availability)) {
                continue;
            }

            // HARD FILTER: must have matching equipment-type skill
            TechnicianSkill skill = technicianSkillMapper.selectByTechnicianAndType(
                    tech.getId(), fault.getEquipmentType());
            if (skill == null) {
                continue;
            }

            // HARD FILTER: certification within 1 level
            if (skill.getCertifiedFaultLevel() != null
                    && fault.getFaultLevel() - skill.getCertifiedFaultLevel() > 1) {
                continue;
            }

            // Calculate all 7 dimensions
            BigDecimal skillScore = calculateSkillScore(skill);
            BigDecimal certScore = calculateCertScore(skill, fault.getFaultLevel());
            BigDecimal availScore = calculateAvailabilityScore(availability);
            BigDecimal workloadScore = calculateWorkloadScore(tech);
            BigDecimal perfScore = calculatePerformanceScore(tech);
            BigDecimal historyBd = BigDecimal.valueOf(historyScore);
            BigDecimal slaBd = BigDecimal.valueOf(slaScore);

            BigDecimal totalScore = skillScore.add(certScore).add(availScore)
                    .add(workloadScore).add(perfScore).add(historyBd).add(slaBd)
                    .setScale(2, RoundingMode.HALF_UP);

            if (totalScore.compareTo(BigDecimal.valueOf(30)) >= 0) {
                String reason = buildRecommendationReason(tech, skill, skillScore, certScore,
                        availScore, workloadScore, perfScore, historyScore, slaScore);

                ScoredCandidate candidate = new ScoredCandidate(tech, totalScore,
                        skillScore, certScore, availScore, workloadScore, perfScore,
                        historyBd, slaBd, estimatedLoss, reason);
                candidates.add(candidate);
            }
        }

        // Sort by total score descending
        candidates.sort(Comparator.comparing(ScoredCandidate::totalScore).reversed());

        // Take top 5 candidates
        int maxPlans = Math.min(candidates.size(), 5);
        List<DispatchPlan> plans = new ArrayList<>();
        for (int i = 0; i < maxPlans; i++) {
            ScoredCandidate c = candidates.get(i);
            DispatchPlan plan = new DispatchPlan();
            plan.setWorkOrderId(workOrder.getId());
            plan.setFaultId(fault.getId());
            plan.setPlanIndex(i + 1);
            plan.setTechnicianId(c.technician().getId());
            plan.setTotalScore(c.totalScore());
            plan.setSkillScore(c.skillScore());
            plan.setCertScore(c.certScore());
            plan.setAvailabilityScore(c.availScore());
            plan.setWorkloadScore(c.workloadScore());
            plan.setPerformanceScore(c.perfScore());
            plan.setHistoryScore(c.historyScore());
            plan.setSlaScore(c.slaScore());
            plan.setEstimatedDowntimeLoss(c.estimatedLoss());
            plan.setRecommendationReason(c.reason());
            plan.setIsRecommended(i == 0 ? 1 : 0);
            plan.setIsSelected(0);
            plan.setCreatedAt(LocalDateTime.now());

            dispatchPlanMapper.insert(plan);
            plans.add(plan);
        }

        log.info("Generated {} dispatch plans for workOrder [{}], best score={}",
                plans.size(), workOrder.getId(),
                plans.isEmpty() ? "N/A" : plans.get(0).getTotalScore());

        // Publish event (deferred until after commit)
        Map<String, Object> payload = new HashMap<>();
        payload.put("workOrderId", workOrder.getId());
        payload.put("faultId", fault.getId());
        payload.put("planCount", plans.size());
        payload.put("recommendedPlanIndex", plans.isEmpty() ? null : plans.get(0).getPlanIndex());
        txPublisher.publish(EventType.DISPATCH_PLANS_GENERATED.name(), payload);

        auditService.log("DISPATCH", "GENERATE_PLANS", "WorkOrder", workOrder.getId(), "SYSTEM",
                "Generated " + plans.size() + " dispatch plans, best score="
                        + (plans.isEmpty() ? "N/A" : plans.get(0).getTotalScore()));

        return plans;
    }

    /**
     * Pre-occupy spare parts before dispatch.
     * High-probability parts are reserved based on fault level and equipment history.
     */
    @Transactional
    public PreOccupyResult preOccupyParts(Long workOrderId, String equipmentType, int faultLevel) {
        List<SparePart> applicableParts = sparePartMapper.selectByEquipmentType(equipmentType);
        List<SparePartOccupation> occupiedParts = new ArrayList<>();
        List<PurchaseSuggestion> shortageParts = new ArrayList<>();

        if (applicableParts == null || applicableParts.isEmpty()) {
            log.info("No applicable spare parts for equipment type [{}], skipping pre-occupation", equipmentType);
            return PreOccupyResult.builder()
                    .workOrderId(workOrderId)
                    .occupiedParts(occupiedParts)
                    .shortageParts(shortageParts)
                    .allPartsAvailable(true)
                    .build();
        }

        boolean isEmergency = faultLevel >= 3;

        // Query fault history for this equipment type to determine frequency
        List<Fault> recentFaults = faultMapper.selectRecentByEquipment(
                workOrderMapper.selectById(workOrderId).getEquipmentId(),
                30 * 24 * 60);
        boolean highFrequency = recentFaults != null && recentFaults.size() >= 3;

        for (SparePart part : applicableParts) {
            int stock = part.getStockQuantity() != null ? part.getStockQuantity() : 0;
            int minStock = part.getMinStock() != null ? part.getMinStock() : 0;

            if (stock == 0) {
                PurchaseSuggestion suggestion = generatePurchaseSuggestion(
                        workOrderId, part, 1, 0);
                shortageParts.add(suggestion);
                log.warn("Spare part [{}] out of stock for workOrder [{}], purchase suggestion created",
                        part.getPartCode(), workOrderId);
                continue;
            }

            boolean shouldOccupy = (isEmergency || highFrequency) || (stock > minStock);

            if (shouldOccupy) {
                try {
                    SparePartOccupation occupation = sparePartService.occupyPart(
                            workOrderId, part.getId(), 1);
                    occupiedParts.add(occupation);
                    log.info("Pre-occupied part [{}] for workOrder [{}]",
                            part.getPartCode(), workOrderId);
                } catch (BusinessException e) {
                    log.warn("Failed to pre-occupy part [{}] for workOrder [{}]: {}",
                            part.getPartCode(), workOrderId, e.getMessage());
                    PurchaseSuggestion suggestion = generatePurchaseSuggestion(
                            workOrderId, part, 1, stock);
                    shortageParts.add(suggestion);
                }
            }
        }

        boolean allAvailable = shortageParts.isEmpty();

        // Publish pre-occupation event (deferred until after commit)
        Map<String, Object> payload = new HashMap<>();
        payload.put("workOrderId", workOrderId);
        payload.put("occupiedCount", occupiedParts.size());
        payload.put("shortageCount", shortageParts.size());
        payload.put("allPartsAvailable", allAvailable);
        txPublisher.publish(EventType.PARTS_PRE_OCCUPIED.name(), payload);

        auditService.log("SPARE_PART", "PRE_OCCUPY", "WorkOrder", workOrderId, "SYSTEM",
                "Pre-occupied " + occupiedParts.size() + " parts, "
                        + shortageParts.size() + " shortage");

        return PreOccupyResult.builder()
                .workOrderId(workOrderId)
                .occupiedParts(occupiedParts)
                .shortageParts(shortageParts)
                .allPartsAvailable(allAvailable)
                .build();
    }

    /**
     * Release all pre-occupied parts for a work order.
     */
    @Transactional
    public void releasePreOccupiedParts(Long workOrderId) {
        try {
            sparePartService.releaseOccupationsByWorkOrder(workOrderId);
            log.info("Pre-occupied parts released for workOrder [{}]", workOrderId);

            Map<String, Object> payload = new HashMap<>();
            payload.put("workOrderId", workOrderId);
            txPublisher.publish(EventType.PARTS_PRE_RELEASED.name(), payload);
        } catch (BusinessException e) {
            log.error("Failed to release pre-occupied parts for workOrder [{}]: {}",
                    workOrderId, e.getMessage());
        }
    }

    /**
     * Select and execute a specific dispatch plan.
     * <p>
     * Fix: Idempotency guard — if a dispatch record with type=AUTO already exists
     * for this work order and the work order already has a technicianId assigned,
     * return a success result based on the existing record instead of creating a duplicate.
     */
    @Transactional
    public DispatchResult selectAndExecutePlan(Long workOrderId, int planIndex) {
        // IDEMPOTENCY: check if already dispatched
        WorkOrder existingOrder = workOrderMapper.selectById(workOrderId);
        if (existingOrder == null) {
            throw new BusinessException("Work order not found: " + workOrderId);
        }
        DispatchRecord existingRecord = dispatchRecordMapper.selectLatestByWorkOrder(workOrderId);
        if (existingRecord != null && existingOrder.getTechnicianId() != null
                && DispatchType.AUTO.name().equals(existingRecord.getDispatchType())) {
            Technician existingTech = technicianService.getById(existingOrder.getTechnicianId());
            String techName = existingTech != null ? existingTech.getName() : "Unknown";
            log.info("Idempotent dispatch: workOrder {} already dispatched to technician {} (recordId={})",
                    workOrderId, existingOrder.getTechnicianId(), existingRecord.getId());
            return DispatchResult.success(workOrderId, existingOrder.getOrderCode(),
                    existingOrder.getTechnicianId(), techName,
                    existingRecord.getDispatchScore(), DispatchType.AUTO.name());
        }

        List<DispatchPlan> plans = dispatchPlanMapper.selectByWorkOrderId(workOrderId);
        DispatchPlan selectedPlan = null;
        for (DispatchPlan plan : plans) {
            if (plan.getPlanIndex() == planIndex) {
                selectedPlan = plan;
                break;
            }
        }

        if (selectedPlan == null) {
            throw new BusinessException("Dispatch plan not found: workOrderId=" + workOrderId
                    + ", planIndex=" + planIndex);
        }

        // Check if technician is still available
        Technician tech = technicianService.getById(selectedPlan.getTechnicianId());
        if (tech == null) {
            throw new BusinessException("Technician not found: " + selectedPlan.getTechnicianId());
        }

        String availability = tech.getAvailability();
        if (TechnicianAvailability.OFFLINE.name().equals(availability)
                || TechnicianAvailability.ON_LEAVE.name().equals(availability)) {
            throw new BusinessException("Selected technician#" + tech.getId()
                    + " is unavailable: " + availability);
        }

        // Mark plan as selected
        dispatchPlanMapper.clearSelected(workOrderId);
        dispatchPlanMapper.updateSelected(selectedPlan.getId());

        WorkOrder workOrder = workOrderMapper.selectById(workOrderId);

        // Create dispatch record
        DispatchRecord record = new DispatchRecord();
        record.setWorkOrderId(workOrderId);
        record.setTechnicianId(selectedPlan.getTechnicianId());
        record.setDispatchType(DispatchType.AUTO.name());
        record.setDispatchScore(selectedPlan.getTotalScore());
        record.setIsAccepted(0);
        record.setCreatedAt(LocalDateTime.now());
        dispatchRecordMapper.insert(record);

        // Update work order
        workOrder.setTechnicianId(selectedPlan.getTechnicianId());
        workOrder.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(workOrder);

        // Update technician workload
        technicianService.incrementWorkload(selectedPlan.getTechnicianId());

        // Publish DISPATCH_DONE event (deferred until after commit)
        Map<String, Object> payload = new HashMap<>();
        payload.put("workOrderId", workOrderId);
        payload.put("orderCode", workOrder.getOrderCode());
        payload.put("technicianId", selectedPlan.getTechnicianId());
        payload.put("technicianName", tech.getName());
        payload.put("dispatchScore", selectedPlan.getTotalScore());
        payload.put("dispatchType", DispatchType.AUTO.name());
        payload.put("planIndex", planIndex);
        txPublisher.publish(EventType.DISPATCH_DONE.name(), payload);

        auditService.log("DISPATCH", "EXECUTE_PLAN", "WorkOrder", workOrderId, "SYSTEM",
                "Executed dispatch plan#" + planIndex + ": technician=" + tech.getName()
                        + ", score=" + selectedPlan.getTotalScore()
                        + ", reason=" + selectedPlan.getRecommendationReason());

        return DispatchResult.success(workOrderId, workOrder.getOrderCode(),
                selectedPlan.getTechnicianId(), tech.getName(),
                selectedPlan.getTotalScore(), DispatchType.AUTO.name());
    }

    /**
     * Generate a purchase suggestion for a shortage part.
     */
    @Transactional
    public PurchaseSuggestion generatePurchaseSuggestion(Long workOrderId, SparePart part,
                                                          int required, int currentStock) {
        int shortage = Math.max(required - currentStock, 0);

        PurchaseSuggestion suggestion = new PurchaseSuggestion();
        suggestion.setWorkOrderId(workOrderId);
        suggestion.setPartId(part.getId());
        suggestion.setPartCode(part.getPartCode());
        suggestion.setPartName(part.getPartName());
        suggestion.setRequiredQuantity(required);
        suggestion.setCurrentStock(currentStock);
        suggestion.setShortageQuantity(shortage);
        suggestion.setUnitPrice(part.getUnitPrice());
        suggestion.setEstimatedCost(part.getUnitPrice() != null
                ? part.getUnitPrice().multiply(BigDecimal.valueOf(shortage))
                : BigDecimal.ZERO);
        suggestion.setUrgency(shortage > 2 ? "CRITICAL" : (shortage > 0 ? "URGENT" : "NORMAL"));
        suggestion.setStatus("PENDING");
        suggestion.setCreatedAt(LocalDateTime.now());

        purchaseSuggestionMapper.insert(suggestion);

        // Publish event (deferred until after commit)
        Map<String, Object> payload = new HashMap<>();
        payload.put("workOrderId", workOrderId);
        payload.put("partId", part.getId());
        payload.put("partCode", part.getPartCode());
        payload.put("partName", part.getPartName());
        payload.put("shortage", shortage);
        payload.put("urgency", suggestion.getUrgency());
        txPublisher.publish(EventType.PURCHASE_SUGGESTED.name(), payload);

        auditService.log("SPARE_PART", "PURCHASE_SUGGESTION", "WorkOrder", workOrderId, "SYSTEM",
                "Purchase suggestion created: part=" + part.getPartCode()
                        + ", shortage=" + shortage + ", urgency=" + suggestion.getUrgency());

        return suggestion;
    }

    /**
     * Get dispatch plans for a work order.
     */
    public List<DispatchPlan> getPlansByWorkOrderId(Long workOrderId) {
        return dispatchPlanMapper.selectByWorkOrderId(workOrderId);
    }

    /**
     * Get purchase suggestions for a work order.
     */
    public List<PurchaseSuggestion> getPurchaseSuggestions(Long workOrderId) {
        return purchaseSuggestionMapper.selectByWorkOrderId(workOrderId);
    }

    // ========== Scoring Methods ==========

    private int calculateHistoryScore(Long equipmentId) {
        List<Fault> recentFaults = faultMapper.selectRecentByEquipment(equipmentId, 30 * 24 * 60);
        int count = recentFaults != null ? recentFaults.size() : 0;

        if (count >= 10) {
            return 15;
        } else if (count >= 5) {
            return 12;
        } else if (count >= 3) {
            return 8;
        } else if (count >= 1) {
            return 4;
        } else {
            return 0;
        }
    }

    private BigDecimal calculateSkillScore(TechnicianSkill skill) {
        BigDecimal score = BigDecimal.valueOf(20);
        int proficiencyBonus = Math.min(skill.getProficiency() * 2, 10);
        return score.add(BigDecimal.valueOf(proficiencyBonus));
    }

    private BigDecimal calculateCertScore(TechnicianSkill skill, int faultLevel) {
        if (skill.getCertifiedFaultLevel() == null) {
            return BigDecimal.valueOf(10);
        }
        int certLevel = skill.getCertifiedFaultLevel();
        if (certLevel >= faultLevel) {
            return BigDecimal.valueOf(20);
        }
        int deficit = faultLevel - certLevel;
        return BigDecimal.valueOf(Math.max(20 - deficit * 10, 0));
    }

    private BigDecimal calculateAvailabilityScore(String availability) {
        if (TechnicianAvailability.AVAILABLE.name().equals(availability)) {
            return BigDecimal.valueOf(25);
        } else if (TechnicianAvailability.BUSY.name().equals(availability)) {
            return BigDecimal.valueOf(10);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal calculateWorkloadScore(Technician tech) {
        int workload = tech.getCurrentWorkload() != null ? tech.getCurrentWorkload() : 0;
        return BigDecimal.valueOf(Math.max(25 - workload * 5, 0));
    }

    private BigDecimal calculatePerformanceScore(Technician tech) {
        int completedCount = workOrderMapper.countByTechnicianAndStatus(
                tech.getId(), WorkOrderStatus.COMPLETED.name());
        if (completedCount >= 20) return BigDecimal.valueOf(25);
        if (completedCount >= 11) return BigDecimal.valueOf(20);
        if (completedCount >= 6) return BigDecimal.valueOf(15);
        if (completedCount >= 1) return BigDecimal.valueOf(10);
        return BigDecimal.valueOf(15);
    }

    private double estimateRepairHours(int faultLevel) {
        return switch (faultLevel) {
            case 1 -> 1.0;
            case 2 -> 2.0;
            case 3 -> 4.0;
            case 4 -> 8.0;
            default -> 2.0;
        };
    }

    private String buildRecommendationReason(Technician tech, TechnicianSkill skill,
                                              BigDecimal skillScore, BigDecimal certScore,
                                              BigDecimal availScore, BigDecimal workloadScore,
                                              BigDecimal perfScore, int historyScore, int slaScore) {
        List<String> reasons = new ArrayList<>();

        if (skillScore.compareTo(BigDecimal.valueOf(25)) >= 0) {
            reasons.add("高技能匹配(proficiency=" + skill.getProficiency() + ")");
        }
        if (certScore.compareTo(BigDecimal.valueOf(20)) == 0) {
            reasons.add("完全认证(认证等级≥故障等级)");
        }
        if (availScore.compareTo(BigDecimal.valueOf(25)) == 0) {
            reasons.add("当前空闲");
        } else if (availScore.compareTo(BigDecimal.valueOf(10)) == 0) {
            reasons.add("当前忙碌但可调度");
        }
        if (workloadScore.compareTo(BigDecimal.valueOf(20)) >= 0) {
            reasons.add("低负载(workload=" + tech.getCurrentWorkload() + ")");
        }
        if (perfScore.compareTo(BigDecimal.valueOf(20)) >= 0) {
            reasons.add("历史绩效优秀");
        }
        if (historyScore >= 8) {
            reasons.add("设备故障频率高需优先处理");
        }
        if (slaScore >= 12) {
            reasons.add("SLA时间紧迫");
        }

        if (reasons.isEmpty()) {
            reasons.add("综合评分最优");
        }

        return String.join(", ", reasons);
    }

    private record ScoredCandidate(Technician technician, BigDecimal totalScore,
                                    BigDecimal skillScore, BigDecimal certScore,
                                    BigDecimal availScore, BigDecimal workloadScore,
                                    BigDecimal perfScore, BigDecimal historyScore,
                                    BigDecimal slaScore, BigDecimal estimatedLoss,
                                    String reason) {}
}
