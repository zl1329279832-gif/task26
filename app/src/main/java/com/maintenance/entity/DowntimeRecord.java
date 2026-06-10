package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("downtime_record")
public class DowntimeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long equipmentId;

    private Long workOrderId;

    private Long faultId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationMinutes;

    private BigDecimal downtimeLoss;

    private LocalDateTime createdAt;
}
