package com.factory.repair.service.impl;

import com.factory.repair.mapper.AuditLogMapper;
import com.factory.repair.model.entity.AuditLog;
import com.factory.repair.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;

    @Async("taskExecutor")
    @Override
    public void log(String entityType, Long entityId, String action, Long operatorId,
                    String operatorName, String beforeData, String afterData, String extraInfo) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setEntityType(entityType);
            auditLog.setEntityId(entityId);
            auditLog.setAction(action);
            auditLog.setOperatorId(operatorId);
            auditLog.setOperatorName(operatorName);
            auditLog.setBeforeData(beforeData);
            auditLog.setAfterData(afterData);
            auditLog.setExtraInfo(extraInfo);
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.error("写入审计日志失败: entityType={}, entityId={}, action={}", entityType, entityId, action, e);
        }
    }

    @Override
    public List<AuditLog> getByEntity(String entityType, Long entityId) {
        return auditLogMapper.selectByEntity(entityType, entityId);
    }
}
