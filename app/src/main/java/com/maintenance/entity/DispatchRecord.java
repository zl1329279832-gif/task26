package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("dispatch_record")
public class DispatchRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workOrderId;

    private Long technicianId;

    private String dispatchType;

    private BigDecimal dispatchScore;

    private Integer isAccepted;

    private Integer responseTime;

    private String rejectedReason;

    private LocalDateTime createdAt;

    private LocalDateTime acceptedAt;
}
