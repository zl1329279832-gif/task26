package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.PurchaseSuggestion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface PurchaseSuggestionMapper extends BaseMapper<PurchaseSuggestion> {

    @Select("SELECT * FROM purchase_suggestion WHERE work_order_id = #{workOrderId}")
    List<PurchaseSuggestion> selectByWorkOrderId(@Param("workOrderId") Long workOrderId);

    @Select("SELECT * FROM purchase_suggestion WHERE work_order_id = #{workOrderId} AND status = #{status}")
    List<PurchaseSuggestion> selectByWorkOrderAndStatus(@Param("workOrderId") Long workOrderId, @Param("status") String status);

    @Update("UPDATE purchase_suggestion SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE purchase_suggestion SET status = 'CANCELLED', updated_at = NOW() WHERE work_order_id = #{workOrderId} AND status = 'PENDING'")
    int cancelPendingByWorkOrder(@Param("workOrderId") Long workOrderId);
}
