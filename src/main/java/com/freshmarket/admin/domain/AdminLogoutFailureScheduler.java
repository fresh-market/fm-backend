package com.freshmarket.admin.domain;

import com.freshmarket.admin.domain.service.AdminLogoutFailureService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * admin_logout_failure에 쌓인 미해결 건을 매일 00:00에 재시도한다.
 * 스케줄러 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다 —
 * KakaoUnlinkRetryScheduler와 같은 이유. 실행/소요시간 로그는 SchedulerLoggingAspect가
 * @Scheduled 메서드마다 자동으로 남긴다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class AdminLogoutFailureScheduler {

    private final AdminLogoutFailureService adminLogoutFailureService;

    /** Todo: 인스턴스 여러 개 뜨면 이 스케줄러가 중복 실행될 수 있다.
     *  지금은 단일 인스턴스라 문제없지만, 배치 전용 인프라(단일 인스턴스 강제 또는 DB 조건부
     *  선점/분산실행 제어)가 갖춰지면 그때 확정한다 (KakaoUnlinkRetryScheduler와 같은 메모).
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void retryPendingLogoutFailures() {
        adminLogoutFailureService.retryAllPending();
    }
}