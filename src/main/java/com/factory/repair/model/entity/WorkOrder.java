package com.factory.repair.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WorkOrder {
    private Long id;
    private String orderNo;
    private Long equipmentId;
    private Long faultTypeId;
    private String faultDescription;
    private Integer faultLevel;
    private String status;
    private Long reporterId;
    private Long assignedWorkerId;
    private Long assignedCrewId;
    private Integer priority;
    private LocalDateTime reportedAt;
    private LocalDateTime dispatchedAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime arrivedAt;
    private LocalDateTime completedAt;
    private LocalDateTime closedAt;
    private Long parentOrderId;
    private String remark;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
