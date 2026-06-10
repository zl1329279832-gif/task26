package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.DispatchPlan;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface DispatchPlanMapper extends BaseMapper<DispatchPlan> {

    @Select("SELECT * FROM dispatch_plan WHERE work_order_id = #{workOrderId} ORDER BY plan_rank ASC")
    List<DispatchPlan> selectByWorkOrderId(@Param("workOrderId") Long workOrderId);

    @Select("SELECT * FROM dispatch_plan WHERE work_order_id = #{workOrderId} AND status = #{status} ORDER BY plan_rank ASC")
    List<DispatchPlan> selectByWorkOrderAndStatus(@Param("workOrderId") Long workOrderId, @Param("status") String status);

    @Select("SELECT * FROM dispatch_plan WHERE fault_id = #{faultId} ORDER BY plan_rank ASC")
    List<DispatchPlan> selectByFaultId(@Param("faultId") Long faultId);

    @Update("UPDATE dispatch_plan SET status = #{status}, selected_at = CASE WHEN #{status} = 'SELECTED' THEN NOW() ELSE selected_at END WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE dispatch_plan SET status = 'EXPIRED' WHERE work_order_id = #{workOrderId} AND status = 'PENDING'")
    int expirePendingPlans(@Param("workOrderId") Long workOrderId);
}
