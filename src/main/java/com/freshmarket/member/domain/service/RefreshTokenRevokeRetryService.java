package com.freshmarket.member.domain.service;

import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.member.domain.entity.RefreshTokenRevokeFailure;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.repository.RefreshTokenRevokeFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
     *
     * member_id + refresh_token_hash에 유니크 제약이 있어서, 같은 토큰의 실패 기록이 거의 동시에
     * 두 번 들어오면 두 트랜잭션이 둘 다 없음을 보고 save()를 시도해 유니크 위반이 날 수 있다.
     * 서로 다른 해시는 각각 별도 행으로 남긴다. 그래야 이전 토큰의 Redis 폐기가 실패한 뒤
     * 재로그인/재폐기 실패가 발생해도 먼저 실패한 토큰의 정리 작업이 유실되지 않는다.
     * MemberLoginService.registerNewMember()와 같은 패턴으로 처리한다 — 위반이 나면 그 사이
     * 먼저 커밋된 행을 다시 찾아 이어서 쓴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long memberId, String role, String refreshTokenHash) {
        try {
            failureRepository.findByMemberIdAndRefreshTokenHash(memberId, refreshTokenHash).ifPresentOrElse(
                    RefreshTokenRevokeFailure::markRetryFailed,
                    () -> failureRepository.save(RefreshTokenRevokeFailure.record(memberId, role, refreshTokenHash)));
        } catch (DataIntegrityViolationException e) {
            failureRepository.findByMemberIdAndRefreshTokenHash(memberId, refreshTokenHash)
                    .ifPresent(RefreshTokenRevokeFailure::markRetryFailed);
        }
    }

    /**
     * shouldGiveUp()으로 건너뛰지 않는다 — 여기서 재시도하는 DB/Redis 정리는 카카오 unlink처럼
     * 외부 API를 계속 두드리는 게 아니라 내부 정리라 비용이 낮고 멱등하다. 인프라가 회복되면
     * 재시도가 자연히 성공해서 행이 지워지므로 무기한 재시도해도 해롭지 않다 — 대신 반복 알림
     * 로그는 RefreshTokenRevokeRetryOutcomeService.markFailed()가 한도를 처음 넘는 순간에만
     * 찍도록 억제한다.
     */
    public void retryAllPending() {
        for (RefreshTokenRevokeFailure failure : failureRepository.findAll()) {
            retryOne(failure);
        }
    }

    private void retryOne(RefreshTokenRevokeFailure failure) {
        boolean dbOk = tryClearDb(failure);
        boolean redisOk = tryClearRedis(failure);
        if (dbOk && redisOk) {
            outcomeService.markSucceeded(failure.getId(), failure.getRefreshTokenHash());
        } else {
            outcomeService.markFailed(failure.getId(), failure.getRefreshTokenHash());
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
            refreshTokenRepository.revokeIfActiveHashMatches(
                    failure.getRefreshTokenHash(), failure.getRole(), failure.getMemberId());
            return true;
        } catch (DataAccessException e) {
            log.warn("event=REFRESH_TOKEN_REVOKE_RETRY_REDIS_FAILED memberId={}", failure.getMemberId(), e);
            return false;
        }
    }
}
