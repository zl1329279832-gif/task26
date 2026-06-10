package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("technician")
public class Technician {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String employeeCode;

    private String name;

    private String phone;

    private String teamCode;

    private Integer skillLevel;

    private String availability;

    private Integer currentWorkload;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
