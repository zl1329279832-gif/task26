package com.factory.repair.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SparePartReservation {
    private Long id;
    private Long workOrderId;
    private Long sparePartId;
    private Integer quantity;
    private String status;
    private LocalDateTime reservedAt;
    private LocalDateTime consumedAt;
    private LocalDateTime releasedAt;
    private LocalDateTime expireAt;
    private String releaseReason;
    private Integer version;
    private LocalDateTime createdAt;
}
