package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("spare_part_occupation")
public class SparePartOccupation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workOrderId;

    private Long partId;

    private Integer quantity;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime consumedAt;

    private LocalDateTime releasedAt;
}
