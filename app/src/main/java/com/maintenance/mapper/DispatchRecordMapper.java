package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.DispatchRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface DispatchRecordMapper extends BaseMapper<DispatchRecord> {

    @Select("SELECT * FROM dispatch_record WHERE work_order_id = #{workOrderId} ORDER BY created_at DESC")
    List<DispatchRecord> selectByWorkOrderId(@Param("workOrderId") Long workOrderId);

    @Select("SELECT * FROM dispatch_record WHERE work_order_id = #{workOrderId} ORDER BY created_at DESC LIMIT 1")
    DispatchRecord selectLatestByWorkOrder(@Param("workOrderId") Long workOrderId);

    @Update("UPDATE dispatch_record SET is_accepted = #{isAccepted}, accepted_at = #{acceptedAt} WHERE id = #{id}")
    int updateAccepted(@Param("id") Long id, @Param("isAccepted") int isAccepted, @Param("acceptedAt") LocalDateTime acceptedAt);
}
