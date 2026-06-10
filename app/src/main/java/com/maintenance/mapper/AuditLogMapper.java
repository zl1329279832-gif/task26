package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.AuditLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AuditLogMapper extends BaseMapper<AuditLog> {

    @Select("SELECT * FROM audit_log WHERE target_type = #{targetType} AND target_id = #{targetId} ORDER BY created_at DESC")
    List<AuditLog> selectByTarget(@Param("targetType") String targetType, @Param("targetId") Long targetId);

    @Select("SELECT * FROM audit_log WHERE module = #{module} ORDER BY created_at DESC LIMIT #{limit}")
    List<AuditLog> selectByModule(@Param("module") String module, @Param("limit") int limit);
}
