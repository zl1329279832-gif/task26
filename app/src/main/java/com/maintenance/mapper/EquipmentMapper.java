package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.Equipment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface EquipmentMapper extends BaseMapper<Equipment> {

    @Select("SELECT * FROM equipment WHERE status = #{status}")
    List<Equipment> selectByStatus(@Param("status") String status);

    @Update("UPDATE equipment SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
