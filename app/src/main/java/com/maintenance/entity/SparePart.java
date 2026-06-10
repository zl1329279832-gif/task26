package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("spare_part")
public class SparePart {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String partCode;

    private String partName;

    private String partType;

    private String applicableEquipmentTypes;

    private Integer stockQuantity;

    private Integer minStock;

    private String location;

    private BigDecimal unitPrice;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
