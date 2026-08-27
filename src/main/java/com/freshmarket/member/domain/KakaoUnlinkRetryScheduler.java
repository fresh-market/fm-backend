package com.freshmarket.member.domain;

import com.freshmarket.member.domain.service.withdrawal.KakaoUnlinkRetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * (2026-08-20, DI-6-02) kakao_unlink_failure에 쌓인 미완료 건을 주기적으로 재시도한다.
 * 스케줄러 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다 —
 * KakaoUnlinkEventListener와 같은 이유. 실행/소요시간 로그는 SchedulerLoggingAspect가 @Scheduled
 * 메서드마다 자동으로 남긴다.
 */
@Component
@RequiredArgsConstructor
public class KakaoUnlinkRetryScheduler {

    private static final long FIXED_DELAY_MS = 10 * 60 * 1000; // 10분

    private final KakaoUnlinkRetryService kakaoUnlinkRetryService;

    /** Todo: 인스턴스 여러 개 뜨면 이 스케줄러가 중복 실행될 수 있다
     *  지금은 단일 인스턴스라 문제 없지만,
     *  배치 전용 인프라(단일 인스턴스 강제 또는 DB 조건부 선점/분산실행 제어)가 갖춰지면 그때 확정한다
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void retryPendingUnlinks() {
        kakaoUnlinkRetryService.retryAllPending();
    }
}
