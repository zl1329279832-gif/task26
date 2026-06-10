package com.factory.repair.controller;

import com.factory.repair.model.dto.FaultReportRequest;
import com.factory.repair.model.entity.WorkOrder;
import com.factory.repair.service.FaultReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/fault-reports")
@RequiredArgsConstructor
public class FaultReportController {

    private final FaultReportService faultReportService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> reportFault(@Valid @RequestBody FaultReportRequest request) {
        WorkOrder workOrder = faultReportService.reportFault(request);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "故障上报成功");
        result.put("workOrderId", workOrder.getId());
        result.put("orderNo", workOrder.getOrderNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
