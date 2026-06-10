package com.factory.repair.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FaultReportRequest {
    @NotNull(message = "设备ID不能为空")
    private Long equipmentId;

    @NotNull(message = "故障类型ID不能为空")
    private Long faultTypeId;

    private String faultDescription;

    private Long reporterId;
}
