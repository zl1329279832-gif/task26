package com.factory.repair.service;

import com.factory.repair.model.entity.AuditLog;
import java.util.List;

public interface AuditLogService {
    void log(String entityType, Long entityId, String action, Long operatorId,
             String operatorName, String beforeData, String afterData, String extraInfo);
    List<AuditLog> getByEntity(String entityType, Long entityId);
}
