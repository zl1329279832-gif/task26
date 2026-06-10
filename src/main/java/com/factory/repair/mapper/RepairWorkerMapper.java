package com.factory.repair.mapper;

import com.factory.repair.model.entity.RepairWorker;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface RepairWorkerMapper {
    RepairWorker selectById(@Param("id") Long id);
    RepairWorker selectByCode(@Param("workerCode") String workerCode);
    List<RepairWorker> selectByCrewId(@Param("crewId") Long crewId);
    List<RepairWorker> selectActiveAndOnline();
    List<RepairWorker> selectAllActive();
    int insert(RepairWorker worker);
    int update(RepairWorker worker);
    int incrementCurrentTasks(@Param("id") Long id);
    int decrementCurrentTasks(@Param("id") Long id);
    int updateOnlineStatus(@Param("id") Long id, @Param("isOnline") Integer isOnline);
}
