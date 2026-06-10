package com.factory.repair.mapper;

import com.factory.repair.model.entity.FaultType;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface FaultTypeMapper {
    FaultType selectById(@Param("id") Long id);
    FaultType selectByCode(@Param("faultCode") String faultCode);
    List<FaultType> selectByEquipmentType(@Param("equipmentType") String equipmentType);
    List<FaultType> selectAll();
    int insert(FaultType faultType);
}
