package com.factory.repair.model.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WorkOrderDTO {
    private Long id;
    private String orderNo;
    private Long equipmentId;
    private String equipmentName;
    private String equipmentCode;
    private Long faultTypeId;
    private String faultName;
    private String faultDescription;
    private Integer faultLevel;
    private String status;
    private String statusDescription;
    private Long assignedWorkerId;
    private String assignedWorkerName;
    private Integer priority;
    private LocalDateTime reportedAt;
    private LocalDateTime dispatchedAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime arrivedAt;
    private LocalDateTime completedAt;
    private LocalDateTime closedAt;
    private Long parentOrderId;
    private String remark;
}
