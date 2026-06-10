package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("work_order")
public class WorkOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderCode;

    private Long faultId;

    private Long equipmentId;

    private Long technicianId;

    private String status;

    private Integer priority;

    private String faultDescription;

    private String repairNotes;

    private Integer reassignCount;

    private Integer escalateCount;

    private Integer isRerepair;

    private Long originalOrderId;

    private BigDecimal partsCost;

    private BigDecimal laborCost;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime acceptedAt;

    private LocalDateTime arrivedAt;

    private LocalDateTime completedAt;

    private LocalDateTime suspendedAt;
}
