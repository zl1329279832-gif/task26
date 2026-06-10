package com.factory.repair.mapper;

import com.factory.repair.model.entity.DowntimeRecord;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface DowntimeRecordMapper {
    DowntimeRecord selectById(@Param("id") Long id);
    List<DowntimeRecord> selectByEquipmentId(@Param("equipmentId") Long equipmentId);
    DowntimeRecord selectByWorkOrderId(@Param("workOrderId") Long workOrderId);
    List<DowntimeRecord> selectOngoing();
    int insert(DowntimeRecord record);
    int update(DowntimeRecord record);
}
