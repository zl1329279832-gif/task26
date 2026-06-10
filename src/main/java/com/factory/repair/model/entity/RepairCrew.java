package com.factory.repair.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RepairCrew {
    private Long id;
    private String crewCode;
    private String crewName;
    private String skills;
    private String shiftType;
    private Integer isActive;
    private LocalDateTime createdAt;
}
