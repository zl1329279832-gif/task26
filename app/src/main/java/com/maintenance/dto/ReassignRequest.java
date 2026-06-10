package com.maintenance.dto;

import lombok.Data;

@Data
public class ReassignRequest {
    private Long newTechnicianId;
    private String reason;
}
