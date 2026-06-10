package com.maintenance.dto;

import lombok.Data;

@Data
public class PartApplyRequest {

    private Long workOrderId;

    private Long partId;

    private Integer quantity;
}
