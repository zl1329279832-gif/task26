package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("purchase_suggestion")
public class PurchaseSuggestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workOrderId;

    private Long partId;

    private Integer suggestedQuantity;

    private String urgencyLevel;

    private Integer currentStock;

    private Integer requiredQuantity;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
