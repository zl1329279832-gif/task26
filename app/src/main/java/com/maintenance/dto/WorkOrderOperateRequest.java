package com.maintenance.dto;

import lombok.Data;

@Data
public class WorkOrderOperateRequest {

    private Long workOrderId;

    private Long technicianId;

    private String notes;

    private String reason;
}
