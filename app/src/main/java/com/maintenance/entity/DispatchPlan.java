package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("dispatch_plan")
public class DispatchPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workOrderId;

    private Long faultId;

    private Long technicianId;

    private Integer planRank;

    private BigDecimal totalScore;

    private BigDecimal skillScore;

    private BigDecimal certScore;

    private BigDecimal availabilityScore;

    private BigDecimal workloadScore;

    private BigDecimal historyScore;

    private BigDecimal partsScore;

    private BigDecimal downtimeCostScore;

    private BigDecimal slaScore;

    private Integer estimatedRepairMinutes;

    private BigDecimal estimatedDowntimeLoss;

    private Integer slaRemainingMinutes;

    private String recommendationReason;

    private String status;

    private LocalDateTime selectedAt;

    private LocalDateTime createdAt;
}
