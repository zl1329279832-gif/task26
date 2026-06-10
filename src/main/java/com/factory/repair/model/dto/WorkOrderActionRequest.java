package com.factory.repair.model.dto;

import lombok.Data;

@Data
public class WorkOrderActionRequest {
    private Long operatorId;
    private String operatorName;
    private Long targetWorkerId;
    private String remark;
}
