package com.maintenance.controller;

import com.maintenance.common.Result;
import com.maintenance.entity.AuditLog;
import com.maintenance.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@Slf4j
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/target")
    public Result<List<AuditLog>> getLogsByTarget(
            @RequestParam String targetType,
            @RequestParam Long targetId) {
        log.info("查询审计日志(按目标), targetType={}, targetId={}", targetType, targetId);
        List<AuditLog> logs = auditService.getLogsByTarget(targetType, targetId);
        return Result.ok(logs);
    }

    @GetMapping("/module")
    public Result<List<AuditLog>> getLogsByModule(
            @RequestParam String module,
            @RequestParam(defaultValue = "50") int limit) {
        log.info("查询审计日志(按模块), module={}, limit={}", module, limit);
        List<AuditLog> logs = auditService.getLogsByModule(module, limit);
        return Result.ok(logs);
    }
}
