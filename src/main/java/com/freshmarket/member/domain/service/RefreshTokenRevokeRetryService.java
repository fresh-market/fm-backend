package com.freshmarket.member.domain.service;

import com.freshmarket.common.auth.jwt.RefreshTokenRepository;
import com.freshmarket.member.domain.entity.RefreshTokenRevokeFailure;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.repository.RefreshTokenRevokeFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * (2026-08-25) MemberTokenService.revoke()의 DB 백업(member.refresh_token_hash)/Redis(기본
 * 레코드·activeKey) 정리 아웃박스. revoke()가 그 자리에서 정리에 실패하면 recordFailure()로
 * 여기 남고, RefreshTokenRevokeRetryScheduler가 주기적으로 retryAllPending()을 불러 재시도한다.
 *
 * DB/Redis 정리 둘 다 원래 멱등이다 — Redis DEL은 키가 없으면 그냥 0을 반환하고, DB 쪽도
 * clearRefreshTokenIfMatches()가 해시 조건부 UPDATE라 이미 지워졌거나 그 사이 재로그인으로
 * 해시가 바뀌었으면 rows-affected 0으로 조용히 끝난다. 그래서 "어느 쪽이 실패했었는지"를 따로
 * 기억하지 않고, 재시도 때마다 둘 다 다시 시도한다 — 이미 성공한 쪽에 한 번 더 쏘는 비용은
 * 무시해도 되는 수준이다.
 *
 * retryAllPending()을 통째로 @Transactional로 묶지 않는다 — Redis 호출(네트워크 대기)이 그 안에
 * 있는데, 트랜잭션 안에서 동기 외부 호출을 하면 그 대기 동안 DB 커넥션이 묶인다(DI-4-02와 같은
 * 이유). 그래서 호출 결과 반영(성공 시 삭제/실패 시 카운트 증가)만 별도 빈
 * (RefreshTokenRevokeRetryOutcomeService)의 짧은 트랜잭션으로 각각 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenRevokeRetryService {

    private final RefreshTokenRevokeFailureRepository failureRepository;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevokeRetryOutcomeService outcomeService;

    /**
     * revoke()가 이미 열어둔 트랜잭션 안에서 호출된다. 바로 직전에 clearRefreshToken()이
     * DataAccessException을 던졌을 수 있어 그 트랜잭션/영속성 컨텍스트가 이미 오염됐을 가능성이
     * 있다 — REQUIRES_NEW로 완전히 새 트랜잭션/커넥션을 써서 이 기록만은 revoke() 쪽 상태와
     * 무관하게 독립적으로 커밋되게 한다(MemberWithdrawalCompletionService를 별도 빈으로 뺀 것과
     * 같은 종류의 이유).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long memberId, String role, String refreshTokenHash) {
        failureRepository.findByMemberId(memberId).ifPresentOrElse(
                existing -> existing.reopen(refreshTokenHash),
                () -> failureRepository.save(RefreshTokenRevokeFailure.record(memberId, role, refreshTokenHash)));
    }

    public void retryAllPending() {
        for (RefreshTokenRevokeFailure failure : failureRepository.findAll()) {
            retryOne(failure);
        }
    }

    private void retryOne(RefreshTokenRevokeFailure failure) {
        boolean dbOk = tryClearDb(failure);
        boolean redisOk = tryClearRedis(failure);
        if (dbOk && redisOk) {
            outcomeService.markSucceeded(failure.getId());
        } else {
            outcomeService.markFailed(failure.getId());
        }
    }

    private boolean tryClearDb(RefreshTokenRevokeFailure failure) {
        try {
            memberRepository.clearRefreshTokenIfMatches(failure.getMemberId(), failure.getRefreshTokenHash());
            return true;
        } catch (DataAccessException e) {
            log.warn("event=REFRESH_TOKEN_REVOKE_RETRY_DB_FAILED memberId={}", failure.getMemberId(), e);
            return false;
        }
    }

    private boolean tryClearRedis(RefreshTokenRevokeFailure failure) {
        try {
            refreshTokenRepository.deleteByHash(failure.getRefreshTokenHash());
            refreshTokenRepository.deleteActiveKey(failure.getRole(), failure.getMemberId());
            return true;
        } catch (DataAccessException e) {
            log.warn("event=REFRESH_TOKEN_REVOKE_RETRY_REDIS_FAILED memberId={}", failure.getMemberId(), e);
            return false;
        }
    }
}
