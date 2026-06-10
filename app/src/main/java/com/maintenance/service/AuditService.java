package com.maintenance.service;

import com.maintenance.entity.AuditLog;
import com.maintenance.mapper.AuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * Record an audit log entry.
     *
     * @param module     the module name (e.g., "FAULT", "WORK_ORDER")
     * @param action     the action performed (e.g., "CREATE", "UPDATE_STATUS")
     * @param targetType the type of the target entity (e.g., "WorkOrder", "Fault")
     * @param targetId   the ID of the target entity
     * @param operator   the user/system performing the action
     * @param detail     additional detail about the action
     */
    public void log(String module, String action, String targetType, Long targetId, String operator, String detail) {
        AuditLog auditLog = new AuditLog();
        auditLog.setModule(module);
        auditLog.setAction(action);
        auditLog.setTargetType(targetType);
        auditLog.setTargetId(targetId);
        auditLog.setOperator(operator);
        auditLog.setDetail(detail);
        auditLog.setCreatedAt(LocalDateTime.now());

        try {
            auditLogMapper.insert(auditLog);
            log.info("Audit log recorded: module={}, action={}, targetType={}, targetId={}, operator={}",
                    module, action, targetType, targetId, operator);
        } catch (Exception e) {
            log.error("Failed to record audit log: module={}, action={}, targetType={}, targetId={}, error={}",
                    module, action, targetType, targetId, e.getMessage(), e);
        }
    }

    /**
     * Get audit logs by target entity type and ID.
     *
     * @param targetType the type of the target entity
     * @param targetId   the ID of the target entity
     * @return list of audit logs ordered by created_at DESC
     */
    public List<AuditLog> getLogsByTarget(String targetType, Long targetId) {
        return auditLogMapper.selectByTarget(targetType, targetId);
    }

    /**
     * Get audit logs by module name with a limit.
     *
     * @param module the module name
     * @param limit  maximum number of records to return
     * @return list of audit logs ordered by created_at DESC
     */
    public List<AuditLog> getLogsByModule(String module, int limit) {
        return auditLogMapper.selectByModule(module, limit);
    }
}
