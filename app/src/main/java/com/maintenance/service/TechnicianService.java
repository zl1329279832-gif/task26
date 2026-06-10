package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.entity.Technician;
import com.maintenance.entity.TechnicianSkill;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.mapper.TechnicianMapper;
import com.maintenance.mapper.TechnicianSkillMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class TechnicianService {

    private final TechnicianMapper technicianMapper;
    private final TechnicianSkillMapper technicianSkillMapper;
    private final AuditService auditService;

    public TechnicianService(TechnicianMapper technicianMapper,
                             TechnicianSkillMapper technicianSkillMapper,
                             AuditService auditService) {
        this.technicianMapper = technicianMapper;
        this.technicianSkillMapper = technicianSkillMapper;
        this.auditService = auditService;
    }

    /**
     * Update the availability status of a technician.
     */
    @Transactional
    public void updateAvailability(Long technicianId, String availability) {
        Technician technician = technicianMapper.selectById(technicianId);
        if (technician == null) {
            throw new BusinessException("Technician not found, technicianId=" + technicianId);
        }
        String oldAvailability = technician.getAvailability();
        technicianMapper.updateAvailability(technicianId, availability);
        log.info("Technician [{}] availability changed from [{}] to [{}]",
                technicianId, oldAvailability, availability);

        auditService.log("TECHNICIAN", "UPDATE_AVAILABILITY", "Technician", technicianId, "SYSTEM",
                "Availability changed: " + oldAvailability + " -> " + availability);
    }

    /**
     * Increment the current workload of a technician by 1.
     */
    @Transactional
    public void incrementWorkload(Long technicianId) {
        technicianMapper.updateWorkload(technicianId, 1);
        log.info("Technician [{}] workload incremented (+1)", technicianId);
    }

    /**
     * Decrement the current workload of a technician by 1 (minimum 0).
     */
    @Transactional
    public void decrementWorkload(Long technicianId) {
        technicianMapper.updateWorkload(technicianId, -1);
        log.info("Technician [{}] workload decremented (-1)", technicianId);
    }

    /**
     * Get all skills for a technician.
     */
    public List<TechnicianSkill> getSkills(Long technicianId) {
        return technicianSkillMapper.selectByTechnicianId(technicianId);
    }

    /**
     * Check if a technician is online (availability != OFFLINE).
     */
    public boolean isOnline(Long technicianId) {
        Technician technician = technicianMapper.selectById(technicianId);
        if (technician == null) {
            return false;
        }
        return !TechnicianAvailability.OFFLINE.name().equals(technician.getAvailability());
    }

    /**
     * Get all available technicians (availability = AVAILABLE).
     */
    public List<Technician> getAvailableTechnicians() {
        return technicianMapper.selectByAvailability(TechnicianAvailability.AVAILABLE.name());
    }

    /**
     * Get a technician by ID.
     */
    public Technician getById(Long id) {
        return technicianMapper.selectById(id);
    }

    /**
     * Get all technicians.
     */
    public List<Technician> getAll() {
        return technicianMapper.selectList(null);
    }
}
