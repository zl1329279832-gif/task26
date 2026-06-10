package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.SparePartOccupation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface SparePartOccupationMapper extends BaseMapper<SparePartOccupation> {

    @Select("SELECT * FROM spare_part_occupation WHERE work_order_id = #{workOrderId}")
    List<SparePartOccupation> selectByWorkOrderId(@Param("workOrderId") Long workOrderId);

    @Select("SELECT * FROM spare_part_occupation WHERE work_order_id = #{workOrderId} AND status = #{status}")
    List<SparePartOccupation> selectByWorkOrderAndStatus(@Param("workOrderId") Long workOrderId, @Param("status") String status);

    @Update("UPDATE spare_part_occupation SET status = #{status}, consumed_at = CASE WHEN #{status} = 'CONSUMED' THEN #{time} ELSE consumed_at END, released_at = CASE WHEN #{status} = 'RELEASED' THEN #{time} ELSE released_at END WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("time") LocalDateTime time);

    @Update("UPDATE spare_part_occupation SET status = #{newStatus}, consumed_at = CASE WHEN #{newStatus} = 'CONSUMED' THEN #{time} ELSE consumed_at END, released_at = CASE WHEN #{newStatus} = 'RELEASED' THEN #{time} ELSE released_at END WHERE work_order_id = #{workOrderId} AND status = #{oldStatus}")
    int batchUpdateStatus(@Param("workOrderId") Long workOrderId, @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus, @Param("time") LocalDateTime time);
}
