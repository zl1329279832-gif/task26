package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.SlaRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface SlaRecordMapper extends BaseMapper<SlaRecord> {

    @Select("SELECT * FROM sla_record WHERE work_order_id = #{workOrderId}")
    SlaRecord selectByWorkOrderId(@Param("workOrderId") Long workOrderId);

    @Select("SELECT * FROM sla_record WHERE work_order_id = #{workOrderId} AND status = 'ACTIVE'")
    SlaRecord selectActiveByWorkOrder(@Param("workOrderId") Long workOrderId);

    @Update("UPDATE sla_record SET status = #{status}, remaining_minutes = #{remainingMinutes}, paused_at = #{pausedAt}, resumed_at = #{resumedAt}, sla_deadline = #{slaDeadline} WHERE id = #{id}")
    int updateSla(@Param("id") Long id,
                  @Param("status") String status,
                  @Param("remainingMinutes") Integer remainingMinutes,
                  @Param("pausedAt") LocalDateTime pausedAt,
                  @Param("resumedAt") LocalDateTime resumedAt,
                  @Param("slaDeadline") LocalDateTime slaDeadline);

    @Update("UPDATE sla_record SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
