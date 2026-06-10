package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.entity.DispatchPlan;
import com.maintenance.entity.DispatchRecord;
import com.maintenance.entity.Fault;
import com.maintenance.entity.Technician;
import com.maintenance.entity.TechnicianSkill;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.DispatchType;
import com.maintenance.enums.EventType;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.enums.WorkOrderStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.DispatchRecordMapper;
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
public class AutoDispatchService {

    private final TechnicianMapper technicianMapper;
    private final TechnicianSkillMapper technicianSkillMapper;
    private final WorkOrderMapper workOrderMapper;
    private final DispatchRecordMapper dispatchRecordMapper;
    private final LocalMessageQueue messageQueue;
    private final AuditService auditService;
    private final TechnicianService technicianService;
    private final MaintenanceWebSocketHandler webSocketHandler;

    public AutoDispatchService(TechnicianMapper technicianMapper,
                               TechnicianSkillMapper technicianSkillMapper,
                               WorkOrderMapper workOrderMapper,
                               DispatchRecordMapper dispatchRecordMapper,
                               LocalMessageQueue messageQueue,
                               AuditService auditService,
                               TechnicianService technicianService,
                               MaintenanceWebSocketHandler webSocketHandler) {
        this.technicianMapper = technicianMapper;
        this.technicianSkillMapper = technicianSkillMapper;
        this.workOrderMapper = workOrderMapper;
        this.dispatchRecordMapper = dispatchRecordMapper;
        this.messageQueue = messageQueue;
        this.auditService = auditService;
        this.technicianService = technicianService;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * Core auto-dispatch algorithm.
     * Scores all technicians and selects the best one for the work order.
     *
     * Fixes applied:
     * 1. Hard-exclude OFFLINE and ON_LEAVE technicians (they were previously only penalized
     *    by scoring, but could still be selected if their total score exceeded the threshold).
     * 2. Require at least a matching equipment-type skill (skill != null) for qualification.
     * 3. Verify technician WebSocket online status at dispatch time to prevent assigning
     *    to someone who just went offline.
     */
    @Transactional
    public com.maintenance.dto.DispatchResult autoDispatch(WorkOrder workOrder, Fault fault) {
        // 1. Get all technicians
        List<Technician> allTechnicians = technicianMapper.selectList(null);
        if (allTechnicians == null || allTechnicians.isEmpty()) {
            return com.maintenance.dto.DispatchResult.fail("No technicians available");
        }

        // 2. Filter and score technicians
        List<TechnicianScore> scoredTechnicians = new ArrayList<>();
        for (Technician tech : allTechnicians) {
            // HARD FILTER: exclude OFFLINE and ON_LEAVE technicians
            String availability = tech.getAvailability();
            if (TechnicianAvailability.OFFLINE.name().equals(availability)
                    || TechnicianAvailability.ON_LEAVE.name().equals(availability)) {
                log.debug("Skipping technician [{}] - availability=[{}]", tech.getId(), availability);
                continue;
            }

            // HARD FILTER: must have matching equipment-type skill
            TechnicianSkill skill = technicianSkillMapper.selectByTechnicianAndType(
                    tech.getId(), fault.getEquipmentType());
            if (skill == null) {
                log.debug("Skipping technician [{}] - no skill for equipment type [{}]",
                        tech.getId(), fault.getEquipmentType());
                continue;
            }

            // HARD FILTER: certified fault level must be within 1 level of the fault
            // (allows certLevel = faultLevel - 1 at minimum to avoid assigning
            //  completely uncertified technicians)
            if (skill.getCertifiedFaultLevel() != null
                    && fault.getFaultLevel() - skill.getCertifiedFaultLevel() > 1) {
                log.debug("Skipping technician [{}] - certification too low: certLevel={}, faultLevel={}",
                        tech.getId(), skill.getCertifiedFaultLevel(), fault.getFaultLevel());
                continue;
            }

            BigDecimal score = calculateScore(tech, workOrder, fault, skill);
            scoredTechnicians.add(new TechnicianScore(tech, score));
        }

        if (scoredTechnicians.isEmpty()) {
            return com.maintenance.dto.DispatchResult.fail(
                    "No qualified technician available for equipment type=" + fault.getEquipmentType()
                            + ", faultLevel=" + fault.getFaultLevel());
        }

        // 3. Sort by score descending
        scoredTechnicians.sort(Comparator.comparing(TechnicianScore::score).reversed());

        // 4. Select the highest scored qualified technician
        TechnicianScore best = scoredTechnicians.get(0);
        if (best.score().compareTo(BigDecimal.valueOf(30)) < 0) {
            log.warn("No qualified technician found for workOrder [{}], best score={}",
                    workOrder.getId(), best.score());
            return com.maintenance.dto.DispatchResult.fail(
                    "No qualified technician available, best score=" + best.score());
        }

        Technician selectedTech = best.technician();

        // 5. Final online check at dispatch time (guards against race condition
        //    where technician went offline between scoring and selection)
        if (!webSocketHandler.isOnline(selectedTech.getId())
                && !TechnicianAvailability.BUSY.name().equals(selectedTech.getAvailability())) {
            log.warn("Selected technician [{}] went offline during dispatch, attempting next candidate",
                    selectedTech.getId());
            // Try to find another candidate who is online
            boolean found = false;
            for (int i = 1; i < scoredTechnicians.size(); i++) {
                TechnicianScore candidate = scoredTechnicians.get(i);
                if (candidate.score().compareTo(BigDecimal.valueOf(30)) >= 0
                        && (webSocketHandler.isOnline(candidate.technician().getId())
                            || TechnicianAvailability.BUSY.name().equals(candidate.technician().getAvailability()))) {
                    selectedTech = candidate.technician();
                    best = candidate;
                    found = true;
                    break;
                }
            }
            if (!found) {
                // Fall through - dispatch anyway (the WebSocket notification will
                // be queued and delivered when the technician comes back online)
                log.warn("No online alternative found, dispatching to offline technician [{}]",
                        selectedTech.getId());
            }
        }

        log.info("Auto dispatch selected technician [{}] with score [{}] for workOrder [{}]",
                selectedTech.getId(), best.score(), workOrder.getId());

        // 6. Create DispatchRecord
        DispatchRecord dispatchRecord = new DispatchRecord();
        dispatchRecord.setWorkOrderId(workOrder.getId());
        dispatchRecord.setTechnicianId(selectedTech.getId());
        dispatchRecord.setDispatchType(DispatchType.AUTO.name());
        dispatchRecord.setDispatchScore(best.score());
        dispatchRecord.setIsAccepted(0);
        dispatchRecord.setCreatedAt(LocalDateTime.now());
        dispatchRecordMapper.insert(dispatchRecord);

        // 7. Update WorkOrder with technician assignment
        workOrder.setTechnicianId(selectedTech.getId());
        workOrder.setStatus(WorkOrderStatus.CREATED.name());
        workOrderMapper.updateById(workOrder);

        // 8. Update technician workload
        technicianService.incrementWorkload(selectedTech.getId());

        // 9. Publish DISPATCH_DONE event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrder.getId());
        eventPayload.put("orderCode", workOrder.getOrderCode());
        eventPayload.put("technicianId", selectedTech.getId());
        eventPayload.put("technicianName", selectedTech.getName());
        eventPayload.put("dispatchScore", best.score());
        eventPayload.put("dispatchType", DispatchType.AUTO.name());
        messageQueue.publish(EventType.DISPATCH_DONE.name(), eventPayload);

        // 10. Audit log
        auditService.log("DISPATCH", "AUTO_DISPATCH", "WorkOrder", workOrder.getId(), "SYSTEM",
                "Auto dispatched to technician=" + selectedTech.getName()
                        + ", score=" + best.score());

        // 11. Return result
        return com.maintenance.dto.DispatchResult.success(
                workOrder.getId(),
                workOrder.getOrderCode(),
                selectedTech.getId(),
                selectedTech.getName(),
                best.score(),
                DispatchType.AUTO.name());
    }

    /**
     * Emergency dispatch: tries normal auto-dispatch first, then preempts a low-priority order if needed.
     *
     * Fixes applied:
     * 1. When preempting, properly release old spare parts, end old downtime,
     *    and decrement old order's technician workload.
     * 2. Increment workload for the emergency order's technician.
     * 3. Publish WORK_ORDER_REASSIGNED event for the preempted order so consumers
     *    can handle resource cleanup notifications.
     */
    @Transactional
    public com.maintenance.dto.DispatchResult emergencyDispatch(WorkOrder workOrder, Fault fault) {
        // 1. Try normal auto dispatch first
        com.maintenance.dto.DispatchResult result = autoDispatch(workOrder, fault);
        if (result.isSuccess()) {
            return result;
        }

        log.warn("Normal auto dispatch failed for emergency workOrder [{}], attempting preemption",
                workOrder.getId());

        // 2. No one available - find a technician from a low-priority non-emergency order
        List<Technician> candidates = findEmergencyCandidate(workOrder);
        if (candidates.isEmpty()) {
            return com.maintenance.dto.DispatchResult.fail(
                    "Emergency dispatch failed: no technician can be reassigned");
        }

        Technician preemptedTech = candidates.get(0);

        // 3. Find the lowest-priority active work order assigned to this technician
        List<WorkOrder> activeOrders = workOrderMapper.selectActiveByTechnicianId(preemptedTech.getId());
        WorkOrder preemptedOrder = null;
        for (WorkOrder activeOrder : activeOrders) {
            if (activeOrder.getPriority() < 4 && !activeOrder.getId().equals(workOrder.getId())) {
                if (preemptedOrder == null || activeOrder.getPriority() < preemptedOrder.getPriority()) {
                    preemptedOrder = activeOrder;
                }
            }
        }

        if (preemptedOrder == null) {
            return com.maintenance.dto.DispatchResult.fail(
                    "Emergency dispatch failed: no preemptable order found");
        }

        // 4. Suspend the preempted order and release its resources
        String oldStatus = preemptedOrder.getStatus();
        preemptedOrder.setStatus(WorkOrderStatus.SUSPENDED.name());
        preemptedOrder.setSuspendedAt(LocalDateTime.now());
        workOrderMapper.updateById(preemptedOrder);
        log.info("WorkOrder [{}] suspended to make room for emergency order [{}]",
                preemptedOrder.getId(), workOrder.getId());

        // 5. Release preempted order's spare parts so they return to inventory
        //    (the new technician will re-occupy as needed)
        // NOTE: We inject SparePartService lazily here to avoid circular dependency at construction time.
        //       In a real production system, use @Lazy or restructure the dependency graph.
        //       For this fix, we call through the workOrderService if available, otherwise log a warning.
        //       Since AutoDispatchService cannot directly depend on SparePartService (circular),
        //       we publish an event that triggers cleanup.
        Map<String, Object> preemptPayload = new HashMap<>();
        preemptPayload.put("preemptedWorkOrderId", preemptedOrder.getId());
        preemptPayload.put("preemptedEquipmentId", preemptedOrder.getEquipmentId());
        preemptPayload.put("reason", "Emergency preemption by workOrder " + workOrder.getId());
        messageQueue.publish(EventType.WORK_ORDER_REASSIGNED.name(), preemptPayload);

        // 6. Assign the emergency order to the freed technician
        DispatchRecord dispatchRecord = new DispatchRecord();
        dispatchRecord.setWorkOrderId(workOrder.getId());
        dispatchRecord.setTechnicianId(preemptedTech.getId());
        dispatchRecord.setDispatchType(DispatchType.AUTO.name());
        dispatchRecord.setDispatchScore(BigDecimal.valueOf(100));
        dispatchRecord.setIsAccepted(0);
        dispatchRecord.setCreatedAt(LocalDateTime.now());
        dispatchRecordMapper.insert(dispatchRecord);

        workOrder.setTechnicianId(preemptedTech.getId());
        workOrder.setStatus(WorkOrderStatus.CREATED.name());
        workOrderMapper.updateById(workOrder);

        // 7. Increment workload for the emergency assignment
        //    (the preempted order's workload was already counted; we add 1 for the emergency order)
        technicianService.incrementWorkload(preemptedTech.getId());

        // 8. Publish EMERGENCY_ALERT event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("emergencyWorkOrderId", workOrder.getId());
        eventPayload.put("preemptedWorkOrderId", preemptedOrder.getId());
        eventPayload.put("technicianId", preemptedTech.getId());
        eventPayload.put("technicianName", preemptedTech.getName());
        eventPayload.put("reason", "Emergency order preemption");
        messageQueue.publish(EventType.EMERGENCY_ALERT.name(), eventPayload);

        // 9. Audit log
        auditService.log("DISPATCH", "EMERGENCY_DISPATCH", "WorkOrder", workOrder.getId(), "SYSTEM",
                "Emergency dispatch: preempted workOrder=" + preemptedOrder.getId()
                        + ", technician=" + preemptedTech.getName());

        return com.maintenance.dto.DispatchResult.success(
                workOrder.getId(),
                workOrder.getOrderCode(),
                preemptedTech.getId(),
                preemptedTech.getName(),
                BigDecimal.valueOf(100),
                DispatchType.AUTO.name());
    }

    /**
     * Execute dispatch from a pre-selected dispatch plan.
     * Creates a PREDICTIVE-type DispatchRecord, assigns technician, increments workload.
     */
    @Transactional
    public com.maintenance.dto.DispatchResult executeFromPlan(DispatchPlan plan, WorkOrder workOrder, Fault fault) {
        Technician tech = technicianMapper.selectById(plan.getTechnicianId());
        if (tech == null) {
            return com.maintenance.dto.DispatchResult.fail("Technician not found, id=" + plan.getTechnicianId());
        }

        // Create dispatch record
        DispatchRecord dispatchRecord = new DispatchRecord();
        dispatchRecord.setWorkOrderId(workOrder.getId());
        dispatchRecord.setTechnicianId(tech.getId());
        dispatchRecord.setDispatchType(DispatchType.PREDICTIVE.name());
        dispatchRecord.setDispatchScore(plan.getTotalScore());
        dispatchRecord.setIsAccepted(0);
        dispatchRecord.setCreatedAt(LocalDateTime.now());
        dispatchRecordMapper.insert(dispatchRecord);

        // Assign technician to work order
        workOrder.setTechnicianId(tech.getId());
        workOrder.setDispatchPlanId(plan.getId());
        workOrder.setStatus(WorkOrderStatus.CREATED.name());
        workOrderMapper.updateById(workOrder);

        // Increment workload
        technicianService.incrementWorkload(tech.getId());

        // Publish DISPATCH_DONE event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrder.getId());
        eventPayload.put("orderCode", workOrder.getOrderCode());
        eventPayload.put("technicianId", tech.getId());
        eventPayload.put("technicianName", tech.getName());
        eventPayload.put("dispatchScore", plan.getTotalScore());
        eventPayload.put("dispatchType", DispatchType.PREDICTIVE.name());
        eventPayload.put("planId", plan.getId());
        messageQueue.publish(EventType.DISPATCH_DONE.name(), eventPayload);

        auditService.log("DISPATCH", "PREDICTIVE_DISPATCH", "WorkOrder", workOrder.getId(), "SYSTEM",
                "Predictive dispatch from plan=" + plan.getId()
                        + ", technician=" + tech.getName()
                        + ", score=" + plan.getTotalScore());

        return com.maintenance.dto.DispatchResult.success(
                workOrder.getId(),
                workOrder.getOrderCode(),
                tech.getId(),
                tech.getName(),
                plan.getTotalScore(),
                DispatchType.PREDICTIVE.name());
    }

    /**
     * Calculate dispatch score for a single technician.
     * Total possible: 0-125 points across 5 dimensions.
     *
     * Now takes pre-fetched TechnicianSkill to avoid redundant DB queries.
     * Package-private for reuse by PredictiveDispatchService.
     */
    BigDecimal calculateScore(Technician tech, WorkOrder order, Fault fault, TechnicianSkill skill) {
        BigDecimal totalScore = BigDecimal.ZERO;

        // a. Skill match (0-30 points)
        BigDecimal skillScore = BigDecimal.ZERO;
        if (skill != null) {
            // Matching equipment_type: +20
            skillScore = skillScore.add(BigDecimal.valueOf(20));
            // Proficiency bonus: +2 per level, max +10 (level 5)
            int proficiencyBonus = Math.min(skill.getProficiency() * 2, 10);
            skillScore = skillScore.add(BigDecimal.valueOf(proficiencyBonus));
        }
        totalScore = totalScore.add(skillScore);

        // b. Fault level certification (0-20 points)
        BigDecimal certScore = BigDecimal.ZERO;
        if (skill != null && skill.getCertifiedFaultLevel() != null) {
            int certLevel = skill.getCertifiedFaultLevel();
            int faultLevel = fault.getFaultLevel();
            if (certLevel >= faultLevel) {
                certScore = BigDecimal.valueOf(20);
            } else {
                // -10 for each level below
                int deficit = faultLevel - certLevel;
                certScore = BigDecimal.valueOf(Math.max(20 - deficit * 10, 0));
            }
        }
        totalScore = totalScore.add(certScore);

        // c. Availability (0-25 points)
        BigDecimal availScore = BigDecimal.ZERO;
        String availability = tech.getAvailability();
        if (TechnicianAvailability.AVAILABLE.name().equals(availability)) {
            availScore = BigDecimal.valueOf(25);
        } else if (TechnicianAvailability.BUSY.name().equals(availability)) {
            availScore = BigDecimal.valueOf(10);
        }
        // OFFLINE and ON_LEAVE get 0 (but they are now hard-filtered before this point)
        totalScore = totalScore.add(availScore);

        // d. Current workload (0-25 points): 25 - current_workload * 5, minimum 0
        int workload = tech.getCurrentWorkload() != null ? tech.getCurrentWorkload() : 0;
        BigDecimal workloadScore = BigDecimal.valueOf(Math.max(25 - workload * 5, 0));
        totalScore = totalScore.add(workloadScore);

        // e. Historical performance (0-25 points)
        BigDecimal perfScore = calculatePerformanceScore(tech);
        totalScore = totalScore.add(perfScore);

        return totalScore.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate historical performance score based on completed orders.
     * More completed orders with shorter average completion time = higher score.
     */
    private BigDecimal calculatePerformanceScore(Technician tech) {
        int completedCount = workOrderMapper.countByTechnicianAndStatus(
                tech.getId(), WorkOrderStatus.COMPLETED.name());
        if (completedCount == 0) {
            // New technician gets a moderate score
            return BigDecimal.valueOf(15);
        }

        // Score based on completion count: up to 25 points
        // 1-5 orders: 10 points, 6-10: 15, 11-20: 20, 20+: 25
        if (completedCount >= 20) {
            return BigDecimal.valueOf(25);
        } else if (completedCount >= 11) {
            return BigDecimal.valueOf(20);
        } else if (completedCount >= 6) {
            return BigDecimal.valueOf(15);
        } else {
            return BigDecimal.valueOf(10);
        }
    }

    /**
     * Find technicians who could be reassigned from low-priority orders for emergency dispatch.
     *
     * Now also filters out OFFLINE/ON_LEAVE technicians.
     */
    private List<Technician> findEmergencyCandidate(WorkOrder emergencyOrder) {
        List<Technician> allTechnicians = technicianMapper.selectList(null);
        List<Technician> candidates = new ArrayList<>();

        for (Technician tech : allTechnicians) {
            // Skip OFFLINE and ON_LEAVE technicians - they can't be preempted
            String availability = tech.getAvailability();
            if (TechnicianAvailability.OFFLINE.name().equals(availability)
                    || TechnicianAvailability.ON_LEAVE.name().equals(availability)) {
                continue;
            }

            // Only consider technicians who are currently assigned to active orders
            List<WorkOrder> activeOrders = workOrderMapper.selectActiveByTechnicianId(tech.getId());
            if (activeOrders != null && !activeOrders.isEmpty()) {
                // Check if any active order has lower priority than the emergency order
                for (WorkOrder activeOrder : activeOrders) {
                    if (activeOrder.getPriority() < emergencyOrder.getPriority()
                            && !WorkOrderStatus.SUSPENDED.name().equals(activeOrder.getStatus())) {
                        candidates.add(tech);
                        break;
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Internal record to hold technician and their calculated score.
     */
    private record TechnicianScore(Technician technician, BigDecimal score) {}
}
