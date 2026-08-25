package com.freshmarket.product.domain.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * (INF-11-13) 확정 통지가 유실된 PENDING 이미지를 주기적으로 정리한다. 스케줄러 어댑터라 서비스가
 * 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다 — OptionAvailabilitySyncScheduler와
 * 같은 이유. 실행/소요시간 로그는 SchedulerLoggingAspect가 @Scheduled 메서드마다 자동으로 남긴다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class PendingProductImageCleanupScheduler {

    private static final long FIXED_DELAY_MS = 60 * 60 * 1000L; // 1시간

    private final PendingProductImageCleanupService pendingProductImageCleanupService;

    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void cleanupExpiredPending() {
        pendingProductImageCleanupService.cleanupExpiredPending();
    }
}
