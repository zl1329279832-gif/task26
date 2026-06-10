package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.Fault;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface FaultMapper extends BaseMapper<Fault> {

    @Select("SELECT * FROM fault WHERE equipment_id = #{equipmentId} AND created_at >= DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE) ORDER BY created_at DESC")
    List<Fault> selectRecentByEquipment(@Param("equipmentId") Long equipmentId, @Param("minutes") int minutes);

    @Update("UPDATE fault SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE fault SET occurrence_count = occurrence_count + 1, fault_description = CONCAT(fault_description, '\n', #{appendDesc}), updated_at = NOW() WHERE id = #{id}")
    int incrementOccurrenceCount(@Param("id") Long id, @Param("appendDesc") String appendDesc);

    @Select("SELECT * FROM fault WHERE equipment_id = #{equipmentId} ORDER BY created_at DESC LIMIT #{limit}")
    List<Fault> selectHistoryByEquipment(@Param("equipmentId") Long equipmentId, @Param("limit") int limit);

    @Select("SELECT AVG(TIMESTAMPDIFF(MINUTE, wo.created_at, wo.completed_at)) FROM work_order wo " +
            "INNER JOIN fault f ON wo.fault_id = f.id " +
            "WHERE f.equipment_type = #{equipmentType} AND wo.status = 'COMPLETED' AND wo.technician_id = #{technicianId}")
    Integer selectAvgRepairMinutes(@Param("equipmentType") String equipmentType, @Param("technicianId") Long technicianId);
}
