package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.SparePart;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface SparePartMapper extends BaseMapper<SparePart> {

    @Select("SELECT * FROM spare_part WHERE applicable_equipment_types LIKE CONCAT('%', #{equipmentType}, '%')")
    List<SparePart> selectByEquipmentType(@Param("equipmentType") String equipmentType);

    @Update("UPDATE spare_part SET stock_quantity = stock_quantity - #{qty}, updated_at = NOW() WHERE id = #{id} AND stock_quantity >= #{qty}")
    int decreaseStock(@Param("id") Long id, @Param("qty") int qty);

    @Update("UPDATE spare_part SET stock_quantity = stock_quantity + #{qty}, updated_at = NOW() WHERE id = #{id}")
    int increaseStock(@Param("id") Long id, @Param("qty") int qty);

    @Select("SELECT * FROM spare_part WHERE part_code = #{partCode}")
    SparePart selectByPartCode(@Param("partCode") String partCode);

    @Select("SELECT * FROM spare_part WHERE stock_quantity <= min_stock")
    List<SparePart> selectLowStock();
}
