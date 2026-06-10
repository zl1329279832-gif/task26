package com.factory.repair.mapper;

import com.factory.repair.model.entity.RepairCrew;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface RepairCrewMapper {
    RepairCrew selectById(@Param("id") Long id);
    RepairCrew selectByCode(@Param("crewCode") String crewCode);
    List<RepairCrew> selectActive();
    List<RepairCrew> selectAll();
    int insert(RepairCrew crew);
    int update(RepairCrew crew);
}
