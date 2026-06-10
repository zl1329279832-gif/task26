package com.factory.repair.mapper;

import com.factory.repair.model.entity.WorkOrder;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface WorkOrderMapper {
    WorkOrder selectById(@Param("id") Long id);
    WorkOrder selectByOrderNo(@Param("orderNo") String orderNo);
    List<WorkOrder> selectByEquipmentId(@Param("equipmentId") Long equipmentId);
    List<WorkOrder> selectByStatus(@Param("status") String status);
    List<WorkOrder> selectByAssignedWorkerId(@Param("assignedWorkerId") Long assignedWorkerId);
    List<WorkOrder> selectActiveByEquipmentAndFaultType(@Param("equipmentId") Long equipmentId, @Param("faultTypeId") Long faultTypeId);
    List<WorkOrder> selectByParentOrderId(@Param("parentOrderId") Long parentOrderId);
    List<WorkOrder> selectAll(@Param("status") String status, @Param("faultLevel") Integer faultLevel, @Param("equipmentId") Long equipmentId);
    int insert(WorkOrder workOrder);
    int updateStatusWithVersion(@Param("id") Long id, @Param("newStatus") String newStatus, @Param("version") Integer version);
    int update(WorkOrder workOrder);
}
