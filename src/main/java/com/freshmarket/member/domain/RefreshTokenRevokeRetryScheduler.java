package com.freshmarket.member.domain;

import com.freshmarket.member.domain.service.RefreshTokenRevokeRetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * (2026-08-25) refresh_token_revoke_failure에 쌓인 미완료 건을 주기적으로 재시도한다.
 * 스케줄러 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다 —
 * KakaoUnlinkRetryScheduler와 같은 이유. 실행/소요시간 로그는 SchedulerLoggingAspect가 @Scheduled
 * 메서드마다 자동으로 남긴다.
 *
 * 카카오 언링크 아웃박스(10분)와 같은 주기로 맞췄다 — 저쪽은 컴플라이언스성 정리라 하루 한 번도
 * 괜찮지만, 이쪽은 "로그아웃/재사용탐지로 무효화됐어야 할 refreshToken이 아직 살아있을 수 있는"
 * 보안성 창을 좁히는 목적이라 그보다 촘촘해야 한다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling 만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class RefreshTokenRevokeRetryScheduler {

    private static final long FIXED_DELAY_MS = 10 * 60 * 1000; // 10분

    private final RefreshTokenRevokeRetryService refreshTokenRevokeRetryService;

    /** Todo: 인스턴스 여러 개 뜨면 이 스케줄러가 중복 실행될 수 있다
     *  지금은 단일 인스턴스라 문제 없지만,
     *  배치 전용 인프라(단일 인스턴스 강제 또는 DB 조건부 선점/분산실행 제어)가 갖춰지면 그때 확정한다
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void retryPendingRevokes() {
        refreshTokenRevokeRetryService.retryAllPending();
    }
}
