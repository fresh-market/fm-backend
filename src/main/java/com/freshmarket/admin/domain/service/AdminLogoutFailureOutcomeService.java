package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.repository.AdminLogoutFailureRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 실패 재시도의 결과 반영만 담당한다. 결과 UPDATE에도 claim 시각을 조건으로 넣어 lease가 만료된
 * 옛 실행자가 이후 재선점한 실행자의 최신 상태를 덮어쓰지 못하게 한다. Repository의 조건부 UPDATE
 * 자체가 짧은 트랜잭션 경계이므로 외부 Redis 호출은 여기 들어오지 않는다.
 */
@Service
@RequiredArgsConstructor
class AdminLogoutFailureOutcomeService {

    private final AdminLogoutFailureRepository failureRepository;
    private final Clock clock;

    boolean applyOutcome(
            Long failureId,
            LocalDateTime claimedAt,
            boolean dbOk,
            boolean redisOk,
            String latestRefreshTokenHash) {
        LocalDateTime now = LocalDateTime.now(clock);
        return failureRepository.applyOutcomeIfClaimOwned(
                failureId, claimedAt, !dbOk, !redisOk, dbOk && redisOk, latestRefreshTokenHash, now) == 1;
    }

    boolean releaseClaim(Long failureId, LocalDateTime claimedAt) {
        return failureRepository.releaseClaimIfOwned(
                failureId, claimedAt, LocalDateTime.now(clock)) == 1;
    }
}