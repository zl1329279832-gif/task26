package com.factory.repair.mapper;

import com.factory.repair.model.entity.AuditLog;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface AuditLogMapper {
    int insert(AuditLog auditLog);
    List<AuditLog> selectByEntity(@Param("entityType") String entityType, @Param("entityId") Long entityId);
    List<AuditLog> selectByOperator(@Param("operatorId") Long operatorId);
}
