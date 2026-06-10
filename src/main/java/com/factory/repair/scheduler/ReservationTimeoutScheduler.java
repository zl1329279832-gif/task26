package com.factory.repair.scheduler;

import com.factory.repair.service.SparePartReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationTimeoutScheduler {

    private final SparePartReservationService reservationService;

    /**
     * 每10分钟扫描过期的备件占用，自动释放
     */
    @Scheduled(fixedRate = 600000)
    public void releaseExpiredReservations() {
        log.debug("开始扫描过期备件占用...");
        try {
            reservationService.releaseExpired();
        } catch (Exception e) {
            log.error("扫描过期备件占用失败", e);
        }
    }

    /**
     * 每天凌晨2点清理孤立的备件占用（兜底策略）
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOrphanedReservations() {
        log.info("开始兜底清理孤立备件占用...");
        try {
            reservationService.cleanupOrphanedReservations();
        } catch (Exception e) {
            log.error("兜底清理孤立备件占用失败", e);
        }
    }
}
