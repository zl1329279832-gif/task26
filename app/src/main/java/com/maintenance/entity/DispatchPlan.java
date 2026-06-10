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

    private Integer planIndex;

    private Long technicianId;

    private BigDecimal totalScore;

    private BigDecimal skillScore;

    private BigDecimal certScore;

    private BigDecimal availabilityScore;

    private BigDecimal workloadScore;

    private BigDecimal performanceScore;

    private BigDecimal historyScore;

    private BigDecimal slaScore;

    private BigDecimal estimatedDowntimeLoss;

    private String recommendationReason;

    private Integer isRecommended;

    private Integer isSelected;

    private LocalDateTime createdAt;
}
