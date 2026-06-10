package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fault")
public class Fault {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String faultCode;

    private Long equipmentId;

    private String equipmentType;

    private Integer faultLevel;

    private String faultDescription;

    private String reporter;

    private String reporterPhone;

    private String status;

    private Integer occurrenceCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
