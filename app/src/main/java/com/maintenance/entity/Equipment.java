package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("equipment")
public class Equipment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String equipmentCode;

    private String equipmentName;

    private String equipmentType;

    private String location;

    private String status;

    private LocalDate purchaseDate;

    private LocalDateTime lastMaintenanceDate;

    private BigDecimal downtimeCostPerHour;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
