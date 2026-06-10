package com.maintenance.service;

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

    public AutoDispatchService(TechnicianMapper technicianMapper,
                               TechnicianSkillMapper technicianSkillMapper,
                               WorkOrderMapper workOrderMapper,
                               DispatchRecordMapper dispatchRecordMapper,
                               LocalMessageQueue messageQueue,
                               AuditService auditService,
                               TechnicianService technicianService) {
        this.technicianMapper = technicianMapper;
        this.technicianSkillMapper = technicianSkillMapper;
        this.workOrderMapper = workOrderMapper;
        this.dispatchRecordMapper = dispatchRecordMapper;
        this.messageQueue = messageQueue;
        this.auditService = auditService;
        this.technicianService = technicianService;
    }

    /**
     * Core auto-dispatch algorithm.
     * Scores all technicians and selects the best one for the work order.
     */
    @Transactional
    public com.maintenance.dto.DispatchResult autoDispatch(WorkOrder workOrder, Fault fault) {
        // 1. Get all technicians
        List<Technician> allTechnicians = technicianMapper.selectList(null);
        if (allTechnicians == null || allTechnicians.isEmpty()) {
            return com.maintenance.dto.DispatchResult.fail("No technicians available");
        }

        // 2. Calculate score for each technician
        List<TechnicianScore> scoredTechnicians = new ArrayList<>();
        for (Technician tech : allTechnicians) {
            BigDecimal score = calculateScore(tech, workOrder, fault);
            scoredTechnicians.add(new TechnicianScore(tech, score));
        }

        // 3. Sort by score descending
        scoredTechnicians.sort(Comparator.comparing(TechnicianScore::score).reversed());

        // 4. Select the highest scored technician
        TechnicianScore best = scoredTechnicians.get(0);
        if (best.score().compareTo(BigDecimal.valueOf(30)) < 0) {
            log.warn("No qualified technician found for workOrder [{}], best score={}",
                    workOrder.getId(), best.score());
            return com.maintenance.dto.DispatchResult.fail("No qualified technician available, best score=" + best.score());
        }

        Technician selectedTech = best.technician();
        log.info("Auto dispatch selected technician [{}] with score [{}] for workOrder [{}]",
                selectedTech.getId(), best.score(), workOrder.getId());

        // 5. Create DispatchRecord
        DispatchRecord dispatchRecord = new DispatchRecord();
        dispatchRecord.setWorkOrderId(workOrder.getId());
        dispatchRecord.setTechnicianId(selectedTech.getId());
        dispatchRecord.setDispatchType(DispatchType.AUTO.name());
        dispatchRecord.setDispatchScore(best.score());
        dispatchRecord.setIsAccepted(0);
        dispatchRecord.setCreatedAt(LocalDateTime.now());
        dispatchRecordMapper.insert(dispatchRecord);

        // 6. Update WorkOrder with technician assignment
        workOrder.setTechnicianId(selectedTech.getId());
        workOrder.setStatus(WorkOrderStatus.CREATED.name());
        workOrderMapper.updateById(workOrder);

        // 7. Update technician workload
        technicianService.incrementWorkload(selectedTech.getId());

        // 8. Publish DISPATCH_DONE event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("workOrderId", workOrder.getId());
        eventPayload.put("orderCode", workOrder.getOrderCode());
        eventPayload.put("technicianId", selectedTech.getId());
        eventPayload.put("technicianName", selectedTech.getName());
        eventPayload.put("dispatchScore", best.score());
        eventPayload.put("dispatchType", DispatchType.AUTO.name());
        messageQueue.publish(EventType.DISPATCH_DONE.name(), eventPayload);

        // 9. Audit log
        auditService.log("DISPATCH", "AUTO_DISPATCH", "WorkOrder", workOrder.getId(), "SYSTEM",
                "Auto dispatched to technician=" + selectedTech.getName()
                        + ", score=" + best.score());

        // 10. Return result
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

        // 4. Suspend the preempted order
        String oldStatus = preemptedOrder.getStatus();
        preemptedOrder.setStatus(WorkOrderStatus.SUSPENDED.name());
        preemptedOrder.setSuspendedAt(LocalDateTime.now());
        workOrderMapper.updateById(preemptedOrder);
        log.info("WorkOrder [{}] suspended to make room for emergency order [{}]",
                preemptedOrder.getId(), workOrder.getId());

        // 5. Assign the emergency order to the freed technician
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

        // 6. Publish EMERGENCY_ALERT event
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("emergencyWorkOrderId", workOrder.getId());
        eventPayload.put("preemptedWorkOrderId", preemptedOrder.getId());
        eventPayload.put("technicianId", preemptedTech.getId());
        eventPayload.put("technicianName", preemptedTech.getName());
        eventPayload.put("reason", "Emergency order preemption");
        messageQueue.publish(EventType.EMERGENCY_ALERT.name(), eventPayload);

        // 7. Audit log
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
     * Calculate dispatch score for a single technician.
     * Total possible: 0-125 points across 5 dimensions.
     */
    private BigDecimal calculateScore(Technician tech, WorkOrder order, Fault fault) {
        BigDecimal totalScore = BigDecimal.ZERO;

        // a. Skill match (0-30 points)
        BigDecimal skillScore = BigDecimal.ZERO;
        TechnicianSkill skill = technicianSkillMapper.selectByTechnicianAndType(
                tech.getId(), fault.getEquipmentType());
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
        // OFFLINE and ON_LEAVE get 0
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
     */
    private List<Technician> findEmergencyCandidate(WorkOrder emergencyOrder) {
        List<Technician> allTechnicians = technicianMapper.selectList(null);
        List<Technician> candidates = new ArrayList<>();

        for (Technician tech : allTechnicians) {
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
