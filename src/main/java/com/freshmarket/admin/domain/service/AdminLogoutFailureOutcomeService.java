package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.repository.AdminLogoutFailureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * AdminLogoutFailureService.retryAllPending()이 Redis/DB를 재시도한 "결과"만 짧은 트랜잭션으로
 * 반영하는 전용 빈. 별도 빈으로 뺀 이유는 KakaoUnlinkRetryOutcomeService와 같다 — 같은 클래스
 * 안에서 this.xxx()로 @Transactional 메서드를 불러봐야 프록시를 안 거쳐서 트랜잭션이 조용히
 * 무시된다(Spring AOP 자기 자신 호출 한계).
 */
@Service
@RequiredArgsConstructor
class AdminLogoutFailureOutcomeService {

    private final AdminLogoutFailureRepository failureRepository;

    @Transactional
    void applyOutcome(Long failureId, boolean dbOk, boolean redisOk, String latestRefreshTokenHash) {
        failureRepository.findById(failureId)
                .ifPresent(failure -> failure.applyRetryOutcome(dbOk, redisOk, latestRefreshTokenHash));
    }

    @Transactional
    void releaseClaim(Long failureId) {
        failureRepository.findById(failureId)
                .ifPresent(failure -> failure.releaseProcessing());
    }
}