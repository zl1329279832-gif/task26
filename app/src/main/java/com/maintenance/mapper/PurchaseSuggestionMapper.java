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

    @Update("UPDATE purchase_suggestion SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
