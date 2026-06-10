package com.factory.repair.mapper;

import com.factory.repair.model.entity.SparePartReservation;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface SparePartReservationMapper {
    SparePartReservation selectById(@Param("id") Long id);
    List<SparePartReservation> selectByWorkOrderId(@Param("workOrderId") Long workOrderId);
    List<SparePartReservation> selectReservedByWorkOrderId(@Param("workOrderId") Long workOrderId);
    List<SparePartReservation> selectExpired();
    List<SparePartReservation> selectOrphanedReservations();
    int insert(SparePartReservation reservation);
    int updateStatusWithVersion(@Param("id") Long id, @Param("status") String status, @Param("version") Integer version);
    int update(SparePartReservation reservation);
}
