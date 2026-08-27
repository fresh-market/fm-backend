package com.freshmarket.product.domain.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * (INF-11-13) 확정 통지가 유실된 PENDING 이미지를 주기적으로 정리한다. 스케줄러 어댑터라 서비스가
 * 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다 — OptionAvailabilitySyncScheduler와
 * 같은 이유. 실행/소요시간 로그는 SchedulerLoggingAspect가 @Scheduled 메서드마다 자동으로 남긴다.
 *
 * 팀 방침(배치 사용 최소화)에 따라 매시간이 아니라 하루 한 번만 돈다 — 정리 대상 기준(유예 시간)
 * 자체가 시간 단위라, 매시간 훑어도 하루 한 번 훑는 것과 결과가 사실상 같다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class PendingProductImageCleanupScheduler {

    private final PendingProductImageCleanupService pendingProductImageCleanupService;

    // 매일 새벽 4시(KST)에 실행한다. AdminLotExpireScheduler(새벽 3시)와 겹치지 않게 한 시간 띄웠다.
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void cleanupExpiredPending() {
        pendingProductImageCleanupService.cleanupExpiredPending();
    }
}
