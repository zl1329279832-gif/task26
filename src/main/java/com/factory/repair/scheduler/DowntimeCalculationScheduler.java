package com.factory.repair.scheduler;

import com.factory.repair.service.DowntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DowntimeCalculationScheduler {

    private final DowntimeService downtimeService;

    /**
     * 每15分钟刷新进行中的停机记录损失金额
     */
    @Scheduled(fixedRate = 900000)
    public void refreshDowntimeLoss() {
        log.debug("开始刷新停机损失...");
        try {
            downtimeService.refreshOngoingDowntime();
        } catch (Exception e) {
            log.error("刷新停机损失失败", e);
        }
    }
}
