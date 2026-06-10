package com.maintenance.controller;

import com.maintenance.common.Result;
import com.maintenance.dto.PartApplyRequest;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.service.SparePartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/parts")
@Slf4j
public class SparePartController {

    private final SparePartService sparePartService;

    public SparePartController(SparePartService sparePartService) {
        this.sparePartService = sparePartService;
    }

    @PostMapping("/occupy")
    public Result<SparePartOccupation> occupyPart(@RequestBody PartApplyRequest request) {
        log.info("占用备件, workOrderId={}, partId={}, quantity={}",
                request.getWorkOrderId(), request.getPartId(), request.getQuantity());
        SparePartOccupation occupation = sparePartService.occupyPart(
                request.getWorkOrderId(), request.getPartId(), request.getQuantity());
        return Result.ok(occupation);
    }

    @PostMapping("/{id}/consume")
    public Result<Void> consumePart(@PathVariable Long id) {
        log.info("消耗备件, occupationId={}", id);
        sparePartService.consumePart(id);
        return Result.ok();
    }

    @PostMapping("/release/{orderId}")
    public Result<Void> releaseOccupations(@PathVariable Long orderId) {
        log.info("释放备件占用, workOrderId={}", orderId);
        sparePartService.releaseOccupationsByWorkOrder(orderId);
        return Result.ok();
    }

    @GetMapping("/occupations/{orderId}")
    public Result<List<SparePartOccupation>> getOccupations(@PathVariable Long orderId) {
        log.info("查询工单备件占用, workOrderId={}", orderId);
        List<SparePartOccupation> occupations = sparePartService.getOccupationsByWorkOrder(orderId);
        return Result.ok(occupations);
    }

    @GetMapping("/query")
    public Result<List<SparePart>> queryParts(
            @RequestParam(required = false) String partType,
            @RequestParam(required = false) String equipmentType,
            @RequestParam(required = false) Boolean lowStockOnly) {
        log.info("查询备件, partType={}, equipmentType={}, lowStockOnly={}", partType, equipmentType, lowStockOnly);
        List<SparePart> parts = sparePartService.queryParts(partType, equipmentType, lowStockOnly);
        return Result.ok(parts);
    }

    @GetMapping("/{id}")
    public Result<SparePart> getById(@PathVariable Long id) {
        log.info("查询备件详情, id={}", id);
        SparePart part = sparePartService.getById(id);
        return Result.ok(part);
    }
}
