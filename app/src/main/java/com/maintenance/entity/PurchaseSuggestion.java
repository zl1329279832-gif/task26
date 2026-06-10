package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("purchase_suggestion")
public class PurchaseSuggestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workOrderId;

    private Long partId;

    private String partCode;

    private String partName;

    private Integer requiredQuantity;

    private Integer currentStock;

    private Integer shortageQuantity;

    private BigDecimal unitPrice;

    private BigDecimal estimatedCost;

    private String urgency;

    private String status;

    private Integer slaPaused;

    private LocalDateTime createdAt;
}
