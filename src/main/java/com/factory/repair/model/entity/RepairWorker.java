package com.factory.repair.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RepairWorker {
    private Long id;
    private String workerCode;
    private String workerName;
    private Long crewId;
    private String skills;
    private String phone;
    private Integer isOnline;
    private Integer currentTasks;
    private Integer maxTasks;
    private Integer isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
