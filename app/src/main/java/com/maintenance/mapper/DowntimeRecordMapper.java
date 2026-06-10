package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.DowntimeRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

public interface DowntimeRecordMapper extends BaseMapper<DowntimeRecord> {

    @Select("SELECT * FROM downtime_record WHERE equipment_id = #{equipmentId} ORDER BY start_time DESC")
    List<DowntimeRecord> selectByEquipmentId(@Param("equipmentId") Long equipmentId);

    @Select("SELECT * FROM downtime_record WHERE equipment_id = #{equipmentId} AND end_time IS NULL ORDER BY start_time DESC LIMIT 1")
    DowntimeRecord selectActiveByEquipment(@Param("equipmentId") Long equipmentId);

    @Select("SELECT COALESCE(SUM(downtime_loss), 0) FROM downtime_record WHERE equipment_id = #{equipmentId}")
    BigDecimal sumLossByEquipment(@Param("equipmentId") Long equipmentId);
}
