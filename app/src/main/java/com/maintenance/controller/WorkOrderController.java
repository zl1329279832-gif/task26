package com.maintenance.controller;

import com.maintenance.common.Result;
import com.maintenance.dto.CompleteRequest;
import com.maintenance.dto.ReassignRequest;
import com.maintenance.dto.SuspendRequest;
import com.maintenance.dto.WorkOrderOperateRequest;
import com.maintenance.entity.WorkOrder;
import com.maintenance.service.WorkOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/work-orders")
@Slf4j
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    public WorkOrderController(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    @GetMapping("/{id}")
    public Result<WorkOrder> getById(@PathVariable Long id) {
        log.info("查询工单详情, id={}", id);
        WorkOrder workOrder = workOrderService.getById(id);
        return Result.ok(workOrder);
    }

    @GetMapping("/technician/{id}")
    public Result<List<WorkOrder>> getByTechnician(@PathVariable Long id) {
        log.info("按技术员查询工单, technicianId={}", id);
        List<WorkOrder> workOrders = workOrderService.getByTechnician(id);
        return Result.ok(workOrders);
    }

    @GetMapping("/status/{status}")
    public Result<List<WorkOrder>> getByStatus(@PathVariable String status) {
        log.info("按状态查询工单, status={}", status);
        List<WorkOrder> workOrders = workOrderService.getByStatus(status);
        return Result.ok(workOrders);
    }

    @PostMapping("/{id}/accept")
    public Result<WorkOrder> accept(@PathVariable Long id, @RequestBody WorkOrderOperateRequest request) {
        log.info("接受工单, workOrderId={}, technicianId={}", id, request.getTechnicianId());
        WorkOrder workOrder = workOrderService.accept(id, request.getTechnicianId());
        return Result.ok(workOrder);
    }

    @PostMapping("/{id}/arrive")
    public Result<WorkOrder> arrive(@PathVariable Long id) {
        log.info("工单到达, workOrderId={}", id);
        WorkOrder workOrder = workOrderService.arrive(id);
        return Result.ok(workOrder);
    }

    @PostMapping("/{id}/start-repair")
    public Result<WorkOrder> startRepair(@PathVariable Long id) {
        log.info("开始维修, workOrderId={}", id);
        WorkOrder workOrder = workOrderService.startRepair(id);
        return Result.ok(workOrder);
    }

    @PostMapping("/{id}/suspend")
    public Result<WorkOrder> suspend(@PathVariable Long id, @RequestBody SuspendRequest request) {
        log.info("挂起工单, workOrderId={}, reason={}", id, request.getReason());
        WorkOrder workOrder = workOrderService.suspend(id, request.getReason());
        return Result.ok(workOrder);
    }

    @PostMapping("/{id}/resume")
    public Result<WorkOrder> resume(@PathVariable Long id) {
        log.info("恢复工单, workOrderId={}", id);
        WorkOrder workOrder = workOrderService.resume(id);
        return Result.ok(workOrder);
    }

    @PostMapping("/{id}/complete")
    public Result<WorkOrder> complete(@PathVariable Long id, @RequestBody CompleteRequest request) {
        log.info("完成工单, workOrderId={}", id);
        WorkOrder workOrder = workOrderService.complete(id, request.getRepairNotes(), request.getLaborCost());
        return Result.ok(workOrder);
    }

    @PostMapping("/{id}/reassign")
    public Result<WorkOrder> reassign(@PathVariable Long id, @RequestBody ReassignRequest request) {
        log.info("转派工单, workOrderId={}, newTechnicianId={}", id, request.getNewTechnicianId());
        WorkOrder workOrder = workOrderService.reassign(id, request.getNewTechnicianId(), request.getReason());
        return Result.ok(workOrder);
    }

    @PostMapping("/{id}/escalate")
    public Result<WorkOrder> escalate(@PathVariable Long id) {
        log.info("升级工单, workOrderId={}", id);
        WorkOrder workOrder = workOrderService.escalate(id);
        return Result.ok(workOrder);
    }

    @PostMapping("/{id}/close")
    public Result<WorkOrder> closeAbnormal(@PathVariable Long id, @RequestBody SuspendRequest request) {
        log.info("异常关闭工单, workOrderId={}, reason={}", id, request.getReason());
        WorkOrder workOrder = workOrderService.closeAbnormal(id, request.getReason());
        return Result.ok(workOrder);
    }

    @PostMapping("/{id}/rework")
    public Result<WorkOrder> rework(@PathVariable Long id, @RequestBody SuspendRequest request) {
        log.info("返工工单, workOrderId={}, reason={}", id, request.getReason());
        WorkOrder workOrder = workOrderService.rework(id, request.getReason());
        return Result.ok(workOrder);
    }
}
