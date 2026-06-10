package com.factory.repair.service.impl;

import com.factory.repair.exception.BusinessException;
import com.factory.repair.exception.InsufficientSparePartException;
import com.factory.repair.mapper.SparePartMapper;
import com.factory.repair.mapper.SparePartReservationMapper;
import com.factory.repair.model.entity.SparePart;
import com.factory.repair.model.entity.SparePartReservation;
import com.factory.repair.service.AuditLogService;
import com.factory.repair.service.SparePartReservationService;
import com.factory.repair.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SparePartReservationServiceImpl implements SparePartReservationService {

    private final SparePartMapper sparePartMapper;
    private final SparePartReservationMapper reservationMapper;
    private final RedisLockUtil redisLockUtil;
    private final AuditLogService auditLogService;

    @Value("${factory.spare-part.reservation-expire-hours:24}")
    private int reservationExpireHours;

    @Override
    @Transactional
    public SparePartReservation reserve(Long workOrderId, Long sparePartId, int quantity) {
        String lockKey = "lock:spare_part:" + sparePartId;
        boolean locked = redisLockUtil.tryLock(lockKey, 30, TimeUnit.SECONDS);
        if (!locked) {
            throw new BusinessException("系统繁忙，请稍后重试");
        }
        try {
            SparePart part = sparePartMapper.selectById(sparePartId);
            if (part == null) {
                throw new BusinessException("备件不存在");
            }
            if (part.getAvailableQty() < quantity) {
                throw new InsufficientSparePartException(
                        "备件[" + part.getPartName() + "]库存不足，当前可用: " + part.getAvailableQty(),
                        part.getAvailableQty());
            }

            int rows = sparePartMapper.reserveWithVersion(sparePartId, quantity, part.getVersion());
            if (rows == 0) {
                throw new BusinessException("并发冲突，请重试");
            }

            SparePartReservation reservation = new SparePartReservation();
            reservation.setWorkOrderId(workOrderId);
            reservation.setSparePartId(sparePartId);
            reservation.setQuantity(quantity);
            reservation.setStatus("RESERVED");
            reservation.setReservedAt(LocalDateTime.now());
            reservation.setExpireAt(LocalDateTime.now().plusHours(reservationExpireHours));
            reservation.setVersion(0);
            reservationMapper.insert(reservation);

            auditLogService.log("SparePartReservation", reservation.getId(), "RESERVE",
                    null, null, null,
                    "workOrderId=" + workOrderId + ",sparePartId=" + sparePartId + ",qty=" + quantity, null);

            // 检查是否低于预警线
            SparePart updated = sparePartMapper.selectById(sparePartId);
            if (updated.getAvailableQty() <= updated.getMinStock() && updated.getMinStock() > 0) {
                log.warn("备件低库存预警: partCode={}, available={}, minStock={}",
                        updated.getPartCode(), updated.getAvailableQty(), updated.getMinStock());
            }

            return reservation;
        } finally {
            redisLockUtil.unlock(lockKey);
        }
    }

    @Override
    @Transactional
    public void release(Long reservationId, String reason) {
        SparePartReservation res = reservationMapper.selectById(reservationId);
        if (res == null || !"RESERVED".equals(res.getStatus())) {
            return; // 幂等
        }

        String lockKey = "lock:spare_part:" + res.getSparePartId();
        boolean locked = redisLockUtil.tryLock(lockKey, 30, TimeUnit.SECONDS);
        if (!locked) {
            throw new BusinessException("系统繁忙，请稍后重试");
        }
        try {
            res.setStatus("RELEASED");
            res.setReleasedAt(LocalDateTime.now());
            res.setReleaseReason(reason);
            reservationMapper.update(res);

            SparePart part = sparePartMapper.selectById(res.getSparePartId());
            sparePartMapper.releaseWithVersion(res.getSparePartId(), res.getQuantity(), part.getVersion());

            auditLogService.log("SparePartReservation", reservationId, "RELEASE",
                    null, null, null, reason, null);

            log.info("备件占用已释放: reservationId={}, reason={}", reservationId, reason);
        } finally {
            redisLockUtil.unlock(lockKey);
        }
    }

    @Override
    @Transactional
    public void releaseByWorkOrder(Long workOrderId, String reason) {
        List<SparePartReservation> reservations = reservationMapper.selectReservedByWorkOrderId(workOrderId);
        for (SparePartReservation res : reservations) {
            release(res.getId(), reason);
        }
    }

    @Override
    @Transactional
    public void consume(Long reservationId) {
        SparePartReservation res = reservationMapper.selectById(reservationId);
        if (res == null || !"RESERVED".equals(res.getStatus())) {
            throw new BusinessException("占用记录不存在或状态异常");
        }

        String lockKey = "lock:spare_part:" + res.getSparePartId();
        boolean locked = redisLockUtil.tryLock(lockKey, 30, TimeUnit.SECONDS);
        if (!locked) {
            throw new BusinessException("系统繁忙，请稍后重试");
        }
        try {
            res.setStatus("CONSUMED");
            res.setConsumedAt(LocalDateTime.now());
            reservationMapper.update(res);

            SparePart part = sparePartMapper.selectById(res.getSparePartId());
            sparePartMapper.consumeWithVersion(res.getSparePartId(), res.getQuantity(), part.getVersion());

            auditLogService.log("SparePartReservation", reservationId, "CONSUME",
                    null, null, null, "qty=" + res.getQuantity(), null);
        } finally {
            redisLockUtil.unlock(lockKey);
        }
    }

    @Override
    public List<SparePartReservation> getByWorkOrderId(Long workOrderId) {
        return reservationMapper.selectByWorkOrderId(workOrderId);
    }

    @Override
    @Transactional
    public void releaseExpired() {
        List<SparePartReservation> expired = reservationMapper.selectExpired();
        for (SparePartReservation res : expired) {
            try {
                release(res.getId(), "超时自动释放");
            } catch (Exception e) {
                log.error("释放过期占用失败: reservationId={}", res.getId(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("释放过期备件占用: count={}", expired.size());
        }
    }

    @Override
    @Transactional
    public void cleanupOrphanedReservations() {
        List<SparePartReservation> orphaned = reservationMapper.selectOrphanedReservations();
        for (SparePartReservation res : orphaned) {
            try {
                release(res.getId(), "关联工单已终态，兜底释放");
            } catch (Exception e) {
                log.error("兜底释放占用失败: reservationId={}", res.getId(), e);
            }
        }
        if (!orphaned.isEmpty()) {
            log.info("兜底释放孤立备件占用: count={}", orphaned.size());
        }
    }
}
