package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String module;

    private String action;

    private String targetType;

    private Long targetId;

    private String operator;

    private String detail;

    private String ipAddress;

    private LocalDateTime createdAt;
}
