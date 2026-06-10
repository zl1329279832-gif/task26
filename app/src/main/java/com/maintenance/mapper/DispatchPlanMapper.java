package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.DispatchPlan;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface DispatchPlanMapper extends BaseMapper<DispatchPlan> {

    @Select("SELECT * FROM dispatch_plan WHERE work_order_id = #{workOrderId} ORDER BY plan_index ASC")
    List<DispatchPlan> selectByWorkOrderId(@Param("workOrderId") Long workOrderId);

    @Update("UPDATE dispatch_plan SET is_selected = 1 WHERE id = #{id}")
    int updateSelected(@Param("id") Long id);

    @Update("UPDATE dispatch_plan SET is_selected = 0 WHERE work_order_id = #{workOrderId}")
    int clearSelected(@Param("workOrderId") Long workOrderId);
}
