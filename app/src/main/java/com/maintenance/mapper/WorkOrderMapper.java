package com.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maintenance.entity.WorkOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface WorkOrderMapper extends BaseMapper<WorkOrder> {

    @Select("SELECT * FROM work_order WHERE status = #{status} ORDER BY created_at DESC")
    List<WorkOrder> selectByStatus(@Param("status") String status);

    @Select("SELECT * FROM work_order WHERE technician_id = #{technicianId} ORDER BY created_at DESC")
    List<WorkOrder> selectByTechnicianId(@Param("technicianId") Long technicianId);

    @Select("SELECT * FROM work_order WHERE technician_id = #{technicianId} AND status NOT IN ('COMPLETED', 'REASSIGNED', 'CLOSED_ABNORMAL', 'REWORK') ORDER BY created_at DESC")
    List<WorkOrder> selectActiveByTechnicianId(@Param("technicianId") Long technicianId);

    @Update("UPDATE work_order SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Select("SELECT * FROM work_order WHERE equipment_id = #{equipmentId} AND status = #{status} ORDER BY created_at DESC LIMIT 1")
    WorkOrder selectLatestByEquipment(@Param("equipmentId") Long equipmentId, @Param("status") String status);

    @Select("SELECT COUNT(*) FROM work_order WHERE technician_id = #{technicianId} AND status = #{status}")
    int countByTechnicianAndStatus(@Param("technicianId") Long technicianId, @Param("status") String status);
}
