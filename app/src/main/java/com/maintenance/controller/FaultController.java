package com.maintenance.controller;

import com.maintenance.common.Result;
import com.maintenance.dto.FaultReportRequest;
import com.maintenance.entity.Fault;
import com.maintenance.service.FaultService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/faults")
@Slf4j
public class FaultController {

    private final FaultService faultService;

    public FaultController(FaultService faultService) {
        this.faultService = faultService;
    }

    @PostMapping("/report")
    public Result<Fault> reportFault(@RequestBody FaultReportRequest request) {
        log.info("故障上报, equipmentId={}, faultLevel={}", request.getEquipmentId(), request.getFaultLevel());
        Fault fault = faultService.reportFault(request);
        return Result.ok(fault);
    }

    @GetMapping("/{id}")
    public Result<Fault> getById(@PathVariable Long id) {
        log.info("查询故障详情, id={}", id);
        Fault fault = faultService.getById(id);
        return Result.ok(fault);
    }

    @GetMapping("/equipment/{id}")
    public Result<List<Fault>> getByEquipment(@PathVariable Long id) {
        log.info("按设备查询故障, equipmentId={}", id);
        List<Fault> faults = faultService.getByEquipment(id);
        return Result.ok(faults);
    }

    @GetMapping("/status/{status}")
    public Result<List<Fault>> getByStatus(@PathVariable String status) {
        log.info("按状态查询故障, status={}", status);
        List<Fault> faults = faultService.getByStatus(status);
        return Result.ok(faults);
    }
}
