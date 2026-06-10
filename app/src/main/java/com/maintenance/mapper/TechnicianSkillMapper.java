package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.TechnicianSkill;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TechnicianSkillMapper extends BaseMapper<TechnicianSkill> {

    @Select("SELECT * FROM technician_skill WHERE technician_id = #{technicianId}")
    List<TechnicianSkill> selectByTechnicianId(@Param("technicianId") Long technicianId);

    @Select("SELECT * FROM technician_skill WHERE equipment_type = #{equipmentType}")
    List<TechnicianSkill> selectByEquipmentType(@Param("equipmentType") String equipmentType);

    @Select("SELECT * FROM technician_skill WHERE technician_id = #{technicianId} AND equipment_type = #{equipmentType}")
    TechnicianSkill selectByTechnicianAndType(@Param("technicianId") Long technicianId, @Param("equipmentType") String equipmentType);
}
