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
    private final SparePartService sparePartService;

    public AutoDispatchService(TechnicianMapper technicianMapper,
                               TechnicianSkillMapper technicianSkillMapper,
                               WorkOrderMapper workOrderMapper,
                               DispatchRecordMapper dispatchRecordMapper,
                               LocalMessageQueue messageQueue,
                               AuditService auditService,
                               TechnicianService technicianService,
                               SparePartService sparePartService) {
        this.technicianMapper = technicianMapper;
        this.technicianSkillMapper = technicianSkillMapper;
        this.workOrderMapper = workOrderMapper;
        this.dispatchRecordMapper = dispatchRecordMapper;
        this.messageQueue = messageQueue;
        this.auditService = auditService;
        this.technicianService = technicianService;
        this.sparePartService = sparePartService;
    }

    /**
     * Core auto-dispatch algorithm.
     * Hard prerequisites: technician must be online, have matching skill, and certified fault level.
     * Then scores qualified technicians and selects the best one.
     */
    @Transactional
    public com.maintenance.dto.DispatchResult autoDispatch(WorkOrder workOrder, Fault fault) {
        // 1. Get all technicians
        List<Technician> allTechnicians = technicianMapper.selectList(null);
        if (allTechnicians == null || allTechnicians.isEmpty()) {
            log.warn("No technicians in system for workOrder [{}]", workOrder.getId());
            return com.maintenance.dto.DispatchResult.fail("No technicians available");
        }

        // 2. Filter and score: only technicians passing hard prerequisites
        List<TechnicianScore> scoredTechnicians = new ArrayList<>();
        for (Technician tech : allTechnicians) {
            // Hard prerequisite 1: must be AVAILABLE or BUSY (not OFFLINE/ON_LEAVE)
            String availability = tech.getAvailability();
            if (TechnicianAvailability.OFFLINE.name().equals(availability)
                    || TechnicianAvailability.ON_LEAVE.name().equals(availability)) {
                log.debug("Technician [{}] skipped: availability={}", tech.getId(), availability);
                continue;
            }

            // Hard prerequisite 2: must have matching skill for equipment type
            TechnicianSkill skill = technicianSkillMapper.selectByTechnicianAndType(
                    tech.getId(), fault.getEquipmentType());
            if (skill == null) {
                log.debug("Technician [{}] skipped: no skill for equipmentType={}",
                        tech.getId(), fault.getEquipmentType());
                continue;
            }

            // Hard prerequisite 3: certified fault level must cover the fault
            if (skill.getCertifiedFaultLevel() == null
                    || skill.getCertifiedFaultLevel() < fault.getFaultLevel()) {
                log.debug("Technician [{}] skipped: certifiedFaultLevel={} < faultLevel={}",
                        tech.getId(), skill.getCertifiedFaultLevel(), fault.getFaultLevel());
                continue;
            }

            BigDecimal score = calculateScore(tech, skill, workOrder, fault);
            scoredTechnicians.add(new TechnicianScore(tech, score));
        }

        if (scoredTechnicians.isEmpty()) {
            log.warn("No qualified technician found for workOrder [{}], equipmentType={}, faultLevel={}",
                    workOrder.getId(), fault.getEquipmentType(), fault.getFaultLevel());
            return com.maintenance.dto.DispatchResult.fail(
                    "No qualified technician: none online with matching skill and certification");
        }

        // 3. Sort by score descending
        scoredTechnicians.sort(Comparator.comparing(TechnicianScore::score).reversed());

        // 4. Select the highest scored technician
        TechnicianScore best = scoredTechnicians.get(0);
        if (best.score().compareTo(BigDecimal.valueOf(30)) < 0) {
            log.warn("No qualified technician found for workOrder [{}], best score={}",
                    workOrder.getId(), best.score());
            return com.maintenance.dto.DispatchResult.fail(
                    "No qualified technician available, best score=" + best.score());
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
     * Preemption includes releasing spare parts on the preempted order and proper status transitions.
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

        // 2. Find a technician from a low-priority non-emergency order who has matching skills
        List<Technician> allTechnicians = technicianMapper.selectList(null);
        Technician preemptedTech = null;
        WorkOrder preemptedOrder = null;

        for (Technician tech : allTechnicians) {
            // Skip offline/on_leave technicians
            String availability = tech.getAvailability();
            if (TechnicianAvailability.OFFLINE.name().equals(availability)
                    || TechnicianAvailability.ON_LEAVE.name().equals(availability)) {
                continue;
            }

            // Must have matching skill for the emergency order's equipment type
            TechnicianSkill skill = technicianSkillMapper.selectByTechnicianAndType(
                    tech.getId(), fault.getEquipmentType());
            if (skill == null || skill.getCertifiedFaultLevel() == null
                    || skill.getCertifiedFaultLevel() < fault.getFaultLevel()) {
                continue;
            }

            // Find this technician's lowest-priority active (non-suspended) order
            List<WorkOrder> activeOrders = workOrderMapper.selectActiveByTechnicianId(tech.getId());
            if (activeOrders == null || activeOrders.isEmpty()) {
                continue;
            }

            for (WorkOrder activeOrder : activeOrders) {
                // Skip suspended orders, the emergency order itself, and max-priority orders
                if (WorkOrderStatus.SUSPENDED.name().equals(activeOrder.getStatus())
                        || activeOrder.getId().equals(workOrder.getId())
                        || activeOrder.getPriority() >= 4) {
                    continue;
                }
                // Must be lower priority than the emergency order
                if (activeOrder.getPriority() < workOrder.getPriority()) {
                    if (preemptedOrder == null || activeOrder.getPriority() < preemptedOrder.getPriority()) {
                        preemptedTech = tech;
                        preemptedOrder = activeOrder;
                    }
                }
            }
        }

        if (preemptedTech == null || preemptedOrder == null) {
            log.warn("Emergency dispatch failed for workOrder [{}]: no preemptable order found",
                    workOrder.getId());
            // Publish DISPATCH_FAILED event
            Map<String, Object> failPayload = new HashMap<>();
            failPayload.put("workOrderId", workOrder.getId());
            failPayload.put("reason", "Emergency dispatch failed: no preemptable order with qualified technician");
            messageQueue.publish(EventType.DISPATCH_FAILED.name(), failPayload);

            return com.maintenance.dto.DispatchResult.fail(
                    "Emergency dispatch failed: no preemptable order with qualified technician");
        }

        // 3. Suspend the preempted order
        String oldStatus = preemptedOrder.getStatus();
        preemptedOrder.setStatus(WorkOrderStatus.SUSPENDED.name());
        preemptedOrder.setSuspendedAt(LocalDateTime.now());
        preemptedOrder.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(preemptedOrder);
        log.info("WorkOrder [{}] suspended (was {}) to make room for emergency order [{}]",
                preemptedOrder.getId(), oldStatus, workOrder.getId());

        // 4. Release spare parts occupied by the preempted order
        sparePartService.releaseOccupationsByWorkOrder(preemptedOrder.getId());

        // 5. Publish STATUS_CHANGED for the preempted order
        Map<String, Object> suspendPayload = new HashMap<>();
        suspendPayload.put("workOrderId", preemptedOrder.getId());
        suspendPayload.put("orderCode", preemptedOrder.getOrderCode());
        suspendPayload.put("oldStatus", oldStatus);
        suspendPayload.put("newStatus", WorkOrderStatus.SUSPENDED.name());
        suspendPayload.put("technicianId", preemptedTech.getId());
        suspendPayload.put("equipmentId", preemptedOrder.getEquipmentId());
        messageQueue.publish(EventType.STATUS_CHANGED.name(), suspendPayload);

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

        // 7. Publish DISPATCH_DONE event for the emergency order
        Map<String, Object> dispatchPayload = new HashMap<>();
        dispatchPayload.put("workOrderId", workOrder.getId());
        dispatchPayload.put("orderCode", workOrder.getOrderCode());
        dispatchPayload.put("technicianId", preemptedTech.getId());
        dispatchPayload.put("technicianName", preemptedTech.getName());
        dispatchPayload.put("dispatchScore", BigDecimal.valueOf(100));
        dispatchPayload.put("dispatchType", DispatchType.AUTO.name());
        messageQueue.publish(EventType.DISPATCH_DONE.name(), dispatchPayload);

        // 8. Publish EMERGENCY_ALERT event
        Map<String, Object> alertPayload = new HashMap<>();
        alertPayload.put("emergencyWorkOrderId", workOrder.getId());
        alertPayload.put("preemptedWorkOrderId", preemptedOrder.getId());
        alertPayload.put("technicianId", preemptedTech.getId());
        alertPayload.put("technicianName", preemptedTech.getName());
        alertPayload.put("reason", "Emergency order preemption");
        messageQueue.publish(EventType.EMERGENCY_ALERT.name(), alertPayload);

        // 9. Audit log
        auditService.log("DISPATCH", "EMERGENCY_DISPATCH", "WorkOrder", workOrder.getId(), "SYSTEM",
                "Emergency dispatch: preempted workOrder=" + preemptedOrder.getId()
                        + ", technician=" + preemptedTech.getName()
                        + ", released spare parts on preempted order");

        return com.maintenance.dto.DispatchResult.success(
                workOrder.getId(),
                workOrder.getOrderCode(),
                preemptedTech.getId(),
                preemptedTech.getName(),
                BigDecimal.valueOf(100),
                DispatchType.AUTO.name());
    }

    /**
     * Calculate dispatch score for a qualified technician (already passed hard prerequisites).
     * Total possible: 0-80 points across 4 soft dimensions (skill proficiency, availability, workload, performance).
     */
    private BigDecimal calculateScore(Technician tech, TechnicianSkill skill,
                                       WorkOrder order, Fault fault) {
        BigDecimal totalScore = BigDecimal.ZERO;

        // a. Skill proficiency (0-30 points) - skill match is already guaranteed
        BigDecimal skillScore = BigDecimal.valueOf(20);
        int proficiencyBonus = Math.min(skill.getProficiency() * 2, 10);
        skillScore = skillScore.add(BigDecimal.valueOf(proficiencyBonus));
        totalScore = totalScore.add(skillScore);

        // b. Certification margin (0-20 points) - certification is already guaranteed >= faultLevel
        int certMargin = skill.getCertifiedFaultLevel() - fault.getFaultLevel();
        BigDecimal certScore = BigDecimal.valueOf(Math.min(20, 10 + certMargin * 5));
        totalScore = totalScore.add(certScore);

        // c. Availability (0-25 points)
        BigDecimal availScore = BigDecimal.ZERO;
        String availability = tech.getAvailability();
        if (TechnicianAvailability.AVAILABLE.name().equals(availability)) {
            availScore = BigDecimal.valueOf(25);
        } else if (TechnicianAvailability.BUSY.name().equals(availability)) {
            availScore = BigDecimal.valueOf(10);
        }
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
     */
    private BigDecimal calculatePerformanceScore(Technician tech) {
        int completedCount = workOrderMapper.countByTechnicianAndStatus(
                tech.getId(), WorkOrderStatus.COMPLETED.name());
        if (completedCount == 0) {
            return BigDecimal.valueOf(15);
        }
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

    private record TechnicianScore(Technician technician, BigDecimal score) {}
}
