package com.factory.repair.mapper;

import com.factory.repair.model.entity.Equipment;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface EquipmentMapper {
    Equipment selectById(@Param("id") Long id);
    Equipment selectByCode(@Param("equipmentCode") String equipmentCode);
    List<Equipment> selectByType(@Param("equipmentType") String equipmentType);
    List<Equipment> selectAll();
    int insert(Equipment equipment);
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);
    int update(Equipment equipment);
}
