package com.factory.repair.mapper;

import com.factory.repair.model.entity.DispatchRecord;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface DispatchRecordMapper {
    DispatchRecord selectById(@Param("id") Long id);
    List<DispatchRecord> selectByWorkOrderId(@Param("workOrderId") Long workOrderId);
    List<DispatchRecord> selectByWorkerId(@Param("workerId") Long workerId);
    List<DispatchRecord> selectPendingTimeout();
    int insert(DispatchRecord record);
    int updateStatus(@Param("id") Long id, @Param("dispatchStatus") String dispatchStatus, @Param("remark") String remark);
    int cancelByWorkOrderId(@Param("workOrderId") Long workOrderId);
}
