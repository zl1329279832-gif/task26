package com.factory.repair.model.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class DispatchRecord {
    private Long id;
    private Long workOrderId;
    private Long workerId;
    private Long crewId;
    private String dispatchType;
    private String dispatchStatus;
    private BigDecimal score;
    private String scoreDetail;
    private LocalDateTime dispatchedAt;
    private LocalDateTime respondedAt;
    private Integer timeoutMinutes;
    private String remark;
    private LocalDateTime createdAt;
}
