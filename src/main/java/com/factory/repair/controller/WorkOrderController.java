package com.factory.repair.controller;

import com.factory.repair.model.dto.SparePartApplyRequest;
import com.factory.repair.model.dto.WorkOrderActionRequest;
import com.factory.repair.model.dto.WorkOrderDTO;
import com.factory.repair.model.entity.WorkOrder;
import com.factory.repair.service.WorkOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/work-orders")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    @GetMapping("/{id}")
    public ResponseEntity<WorkOrderDTO> getDetail(@PathVariable Long id) {
        return ResponseEntity.ok(workOrderService.getDetail(id));
    }

    @GetMapping
    public ResponseEntity<List<WorkOrderDTO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer faultLevel,
            @RequestParam(required = false) Long equipmentId) {
        return ResponseEntity.ok(workOrderService.listWorkOrders(status, faultLevel, equipmentId));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        workOrderService.accept(id, request);
        return ResponseEntity.ok(successResponse("接单成功"));
    }

    @PostMapping("/{id}/arrive")
    public ResponseEntity<Map<String, Object>> arrive(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        workOrderService.arrive(id, request);
        return ResponseEntity.ok(successResponse("到场确认成功"));
    }

    @PostMapping("/{id}/start-repair")
    public ResponseEntity<Map<String, Object>> startRepair(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        workOrderService.startRepair(id, request);
        return ResponseEntity.ok(successResponse("开始维修"));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        workOrderService.pause(id, request);
        return ResponseEntity.ok(successResponse("工单已暂停"));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        workOrderService.resume(id, request);
        return ResponseEntity.ok(successResponse("工单已恢复"));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        workOrderService.complete(id, request);
        return ResponseEntity.ok(successResponse("完工提交成功"));
    }

    @PostMapping("/{id}/transfer")
    public ResponseEntity<Map<String, Object>> transfer(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        workOrderService.transfer(id, request);
        return ResponseEntity.ok(successResponse("转派成功"));
    }

    @PostMapping("/{id}/escalate")
    public ResponseEntity<Map<String, Object>> escalate(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        workOrderService.escalate(id, request);
        return ResponseEntity.ok(successResponse("工单已升级"));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<Map<String, Object>> close(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        workOrderService.close(id, request);
        return ResponseEntity.ok(successResponse("工单已关闭"));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        workOrderService.cancel(id, request);
        return ResponseEntity.ok(successResponse("工单已取消"));
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<Map<String, Object>> reopen(@PathVariable Long id, @RequestBody WorkOrderActionRequest request) {
        WorkOrder newOrder = workOrderService.reopen(id, request);
        Map<String, Object> result = successResponse("返修工单已创建");
        result.put("newWorkOrderId", newOrder.getId());
        result.put("newOrderNo", newOrder.getOrderNo());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/spare-parts")
    public ResponseEntity<Map<String, Object>> applySparePart(
            @PathVariable Long id,
            @Valid @RequestBody SparePartApplyRequest request,
            @RequestParam(required = false) Long operatorId) {
        workOrderService.applySparePart(id, request, operatorId);
        return ResponseEntity.ok(successResponse("备件申请成功"));
    }

    private Map<String, Object> successResponse(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", message);
        return result;
    }
}
