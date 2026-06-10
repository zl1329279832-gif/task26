package com.maintenance.controller;

import com.maintenance.common.Result;
import com.maintenance.entity.Technician;
import com.maintenance.entity.TechnicianSkill;
import com.maintenance.entity.WorkOrder;
import com.maintenance.service.TechnicianService;
import com.maintenance.service.WorkOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/technicians")
@Slf4j
public class TechnicianController {

    private final TechnicianService technicianService;
    private final WorkOrderService workOrderService;

    public TechnicianController(TechnicianService technicianService, WorkOrderService workOrderService) {
        this.technicianService = technicianService;
        this.workOrderService = workOrderService;
    }

    @GetMapping
    public Result<List<Technician>> getAll() {
        log.info("查询所有维修人员");
        List<Technician> technicians = technicianService.getAll();
        return Result.ok(technicians);
    }

    @GetMapping("/{id}")
    public Result<Technician> getById(@PathVariable Long id) {
        log.info("查询维修人员详情, id={}", id);
        Technician technician = technicianService.getById(id);
        return Result.ok(technician);
    }

    @GetMapping("/available")
    public Result<List<Technician>> getAvailable() {
        log.info("查询可用维修人员");
        List<Technician> technicians = technicianService.getAvailableTechnicians();
        return Result.ok(technicians);
    }

    @GetMapping("/{id}/skills")
    public Result<List<TechnicianSkill>> getSkills(@PathVariable Long id) {
        log.info("查询维修人员技能, technicianId={}", id);
        List<TechnicianSkill> skills = technicianService.getSkills(id);
        return Result.ok(skills);
    }

    @PutMapping("/{id}/availability")
    public Result<Void> updateAvailability(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String availability = body.get("availability");
        log.info("更新维修人员可用状态, technicianId={}, availability={}", id, availability);
        technicianService.updateAvailability(id, availability);
        return Result.ok();
    }

    @GetMapping("/{id}/workload")
    public Result<List<WorkOrder>> getWorkload(@PathVariable Long id) {
        log.info("查询维修人员活跃工单, technicianId={}", id);
        List<WorkOrder> activeOrders = workOrderService.getActiveByTechnician(id);
        return Result.ok(activeOrders);
    }
}
