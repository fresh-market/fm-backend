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

    @Transactional
    public void markFailed(Long failureId) {
        failureRepository.findById(failureId).ifPresent(failure -> {
            failure.markRetryFailed();
            if (failure.shouldGiveUp()) {
                // 이 지점부턴 "조용히"가 아니다 — 로그아웃/재사용탐지로 무효화됐어야 할
                // refreshToken이 계속 안 지워지고 있다는 뜻이라 사람이 봐야 한다.
                log.error("event=REFRESH_TOKEN_REVOKE_OUTBOX_GAVE_UP memberId={} attempts={}",
                        failure.getMemberId(), failure.getAttemptCount());
            } else {
                log.warn("event=REFRESH_TOKEN_REVOKE_OUTBOX_RETRY_FAILED memberId={} attempts={}",
                        failure.getMemberId(), failure.getAttemptCount());
            }
        });
    }
}
