package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.Technician;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TechnicianMapper extends BaseMapper<Technician> {

    @Select("SELECT * FROM technician WHERE availability = #{availability}")
    List<Technician> selectByAvailability(@Param("availability") String availability);

    @Update("UPDATE technician SET availability = #{availability}, updated_at = NOW() WHERE id = #{id}")
    int updateAvailability(@Param("id") Long id, @Param("availability") String availability);

    @Update("UPDATE technician SET current_workload = GREATEST(current_workload + #{delta}, 0), updated_at = NOW() WHERE id = #{id}")
    int updateWorkload(@Param("id") Long id, @Param("delta") int delta);
}
