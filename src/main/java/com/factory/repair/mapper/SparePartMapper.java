package com.factory.repair.mapper;

import com.factory.repair.model.entity.SparePart;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface SparePartMapper {
    SparePart selectById(@Param("id") Long id);
    SparePart selectByCode(@Param("partCode") String partCode);
    List<SparePart> selectByEquipmentType(@Param("equipmentType") String equipmentType);
    List<SparePart> selectLowStock();
    List<SparePart> selectAll();
    int insert(SparePart sparePart);
    int reserveWithVersion(@Param("id") Long id, @Param("qty") int qty, @Param("version") Integer version);
    int releaseWithVersion(@Param("id") Long id, @Param("qty") int qty, @Param("version") Integer version);
    int consumeWithVersion(@Param("id") Long id, @Param("qty") int qty, @Param("version") Integer version);
    int update(SparePart sparePart);
}
