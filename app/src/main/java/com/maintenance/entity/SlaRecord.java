package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sla_record")
public class SlaRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workOrderId;

    private Integer faultLevel;

    private LocalDateTime slaDeadline;

    private Integer remainingMinutes;

    private String status;

    private String pauseReason;

    private LocalDateTime pausedAt;

    private LocalDateTime resumedAt;

    private LocalDateTime createdAt;
}
