package com.factory.repair.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AuditLog {
    private Long id;
    private String entityType;
    private Long entityId;
    private String action;
    private Long operatorId;
    private String operatorName;
    private String beforeData;
    private String afterData;
    private String extraInfo;
    private LocalDateTime createdAt;
}
