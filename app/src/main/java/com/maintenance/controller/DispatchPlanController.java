package com.maintenance.controller;

import com.maintenance.common.BusinessException;
import com.maintenance.common.Result;
import com.maintenance.dto.DispatchResult;
import com.maintenance.entity.DispatchPlan;
import com.maintenance.entity.PurchaseSuggestion;
import com.maintenance.entity.SlaRecord;
import com.maintenance.service.PredictiveDispatchService;
import com.maintenance.service.SlaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dispatch-plans")
@Slf4j
public class DispatchPlanController {

    private final PredictiveDispatchService predictiveDispatchService;
    private final SlaService slaService;

    public DispatchPlanController(PredictiveDispatchService predictiveDispatchService,
                                   SlaService slaService) {
        this.predictiveDispatchService = predictiveDispatchService;
        this.slaService = slaService;
    }

    @GetMapping("/work-order/{workOrderId}")
    public Result<List<DispatchPlan>> getPlans(@PathVariable Long workOrderId) {
        log.info("查询派工方案, workOrderId={}", workOrderId);
        List<DispatchPlan> plans = predictiveDispatchService.getPlansByWorkOrderId(workOrderId);
        return Result.ok(plans);
    }

    @PostMapping("/work-order/{workOrderId}/select")
    public Result<DispatchResult> selectPlan(@PathVariable Long workOrderId,
                                              @RequestParam int planIndex) {
        log.info("选择派工方案, workOrderId={}, planIndex={}", workOrderId, planIndex);
        try {
            DispatchResult result = predictiveDispatchService.selectAndExecutePlan(workOrderId, planIndex);
            return Result.ok(result);
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @GetMapping("/purchase-suggestions/work-order/{workOrderId}")
    public Result<List<PurchaseSuggestion>> getPurchaseSuggestions(@PathVariable Long workOrderId) {
        log.info("查询采购建议, workOrderId={}", workOrderId);
        List<PurchaseSuggestion> suggestions = predictiveDispatchService.getPurchaseSuggestions(workOrderId);
        return Result.ok(suggestions);
    }

    @GetMapping("/sla/work-order/{workOrderId}")
    public Result<SlaRecord> getSlaStatus(@PathVariable Long workOrderId) {
        log.info("查询SLA状态, workOrderId={}", workOrderId);
        SlaRecord record = slaService.getByWorkOrderId(workOrderId);
        return Result.ok(record);
    }
}
