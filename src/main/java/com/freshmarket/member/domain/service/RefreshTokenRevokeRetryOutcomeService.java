package com.freshmarket.member.domain.service;

import com.freshmarket.member.domain.entity.RefreshTokenRevokeFailure;
import com.freshmarket.member.domain.repository.RefreshTokenRevokeFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * (2026-08-25) RefreshTokenRevokeRetryService.retryAllPending()이 Redis/DB를 다시 두드린 "결과"만
 * DB에 반영하는 전용 빈. 별도 빈으로 뺀 이유는 KakaoUnlinkRetryOutcomeService와 같다 — 같은
 * 클래스 안에서 this.xxx()로 @Transactional 메서드를 불러봐야 프록시를 안 거쳐서 트랜잭션이
 * 조용히 무시된다(Spring AOP 자기 자신 호출 한계). retryAllPending()이 이 클래스의 메서드를
 * 부르는 건 다른 빈을 부르는 거라 정상적으로 프록시를 탄다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenRevokeRetryOutcomeService {

    private final RefreshTokenRevokeFailureRepository failureRepository;

    @Transactional
    public void markSucceeded(Long failureId) {
        failureRepository.deleteById(failureId);
    }

    /**
     * (2026-08-25) 이 정리는 카카오 unlink 재시도와 달리 shouldGiveUp()이 돼도 retryAllPending()이
     * 재시도를 멈추지 않는다(RefreshTokenRevokeRetryService 참고 — 내부 DB/Redis 정리라 비용이
     * 낮고 멱등해서, 인프라가 회복되면 자연히 성공해 큐에서 빠진다). 대신 알림 로그까지 매
     * 10분마다 무한히 찍히면 시끄러우므로, 한도를 "처음" 넘는 순간에만 ERROR로 한 번 올리고
     * 그 뒤로 계속 실패해도 더 이상 로그를 남기지 않는다(사람이 이미 한 번 통보받았으므로).
     */
    @Transactional
    public void markFailed(Long failureId) {
        failureRepository.findById(failureId).ifPresent(failure -> {
            boolean belowThresholdBefore = !failure.shouldGiveUp();
            failure.markRetryFailed();
            if (belowThresholdBefore && failure.shouldGiveUp()) {
                // 이 지점부턴 "조용히"가 아니다 — 로그아웃/재사용탐지로 무효화됐어야 할
                // refreshToken이 계속 안 지워지고 있다는 뜻이라 사람이 봐야 한다. 이후 재시도가
                // 계속 실패해도 이 알림은 한 번만 남긴다(아래 else-if 참고).
                log.error("event=REFRESH_TOKEN_REVOKE_OUTBOX_GAVE_UP memberId={} attempts={}",
                        failure.getMemberId(), failure.getAttemptCount());
            } else if (!failure.shouldGiveUp()) {
                log.warn("event=REFRESH_TOKEN_REVOKE_OUTBOX_RETRY_FAILED memberId={} attempts={}",
                        failure.getMemberId(), failure.getAttemptCount());
            }
        });
    }
}
