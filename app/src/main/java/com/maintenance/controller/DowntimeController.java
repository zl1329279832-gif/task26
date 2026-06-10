package com.maintenance.controller;

import com.maintenance.common.Result;
import com.maintenance.entity.DowntimeRecord;
import com.maintenance.service.DowntimeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/downtime")
@Slf4j
public class DowntimeController {

    private final DowntimeService downtimeService;

    public DowntimeController(DowntimeService downtimeService) {
        this.downtimeService = downtimeService;
    }

    @GetMapping("/equipment/{id}")
    public Result<List<DowntimeRecord>> getByEquipment(@PathVariable Long id) {
        log.info("按设备查询停机记录, equipmentId={}", id);
        List<DowntimeRecord> records = downtimeService.getByEquipment(id);
        return Result.ok(records);
    }

    @GetMapping("/loss/{id}")
    public Result<BigDecimal> calculateLoss(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        log.info("计算停机损失, equipmentId={}, startTime={}, endTime={}", id, startTime, endTime);
        BigDecimal loss = downtimeService.calculateLoss(id, startTime, endTime);
        return Result.ok(loss);
    }
}
