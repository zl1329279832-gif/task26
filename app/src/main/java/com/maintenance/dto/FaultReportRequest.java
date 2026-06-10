package com.maintenance.dto;

import lombok.Data;

@Data
public class FaultReportRequest {

    private Long equipmentId;

    private Integer faultLevel;

    private String faultDescription;

    private String reporter;

    private String reporterPhone;
}
