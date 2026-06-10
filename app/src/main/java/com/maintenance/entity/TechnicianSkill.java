package com.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("technician_skill")
public class TechnicianSkill {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long technicianId;

    private String equipmentType;

    private Integer proficiency;

    private Integer certifiedFaultLevel;
}
