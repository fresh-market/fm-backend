package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.logging.SafeExceptionLog;
import com.freshmarket.admin.domain.retry.FullJitterRetryPolicy;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

/*
 * 관리자 Refresh Token의 DB 폐기와 Redis 정리를, 즉시 재시도(각 3회)까지 포함해 담당한다.
 *
 * 원래 AdminAuthService.logout()이 직접 갖고 있던 로직이었는데, 로그아웃 실패 시 아웃박스에
 * 기록해두고 AdminLogoutFailureScheduler가 나중에 "같은 정리 로직"으로 재시도해야 한다.
 * AdminAuthService가 AdminLogoutFailureService를 부르고, AdminLogoutFailureService가 다시
 * AdminAuthService의 정리 로직을 불러야 하면 순환 참조가 생긴다 — 그래서 정리 로직 자체를
 * 이 별도 빈으로 빼서, AdminAuthService와 AdminLogoutFailureService가 각자 이 빈만 갖다 쓰게 한다.
 *
 * DB/Redis 재시도는 짧은 요청 예산 안에서만 수행한다. 매 재시도 전에 지수적으로 커지는 상한 안에서
 * Full Jitter를 적용해 동시에 실패한 요청들이 같은 시점에 저장소를 다시 두드리는 문제를 줄인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AdminRefreshTokenCleanupService {

    private static final int MAX_DB_REVOKE_ATTEMPTS = 3;
    private static final int MAX_REDIS_CLEANUP_ATTEMPTS = 3;

    // 즉시 재시도 전체가 로그아웃 요청을 오래 점유하지 않도록 짧은 예산 안에서만 수행한다.
    private static final Duration RETRY_BUDGET = Duration.ofMillis(500);
    private static final Duration RETRY_BASE_DELAY = Duration.ofMillis(25);
    private static final Duration RETRY_MAX_DELAY = Duration.ofMillis(100);

    // Redis 정리/차단 로그에서 반복되는 필드 포맷
    private static final String LOG_FIELDS_ROLE_ADMIN_ID = "role={} adminId={}";
    private static final String LOG_FIELDS_TARGET_ROLE_ADMIN_ID = "target={} role={} adminId={}";

    private final RefreshTokenRepository refreshTokenRepository;
    private final AdminLogoutTransactionService adminLogoutTransactionService;
    private final FullJitterRetryPolicy retryPolicy;

    /*
     * DB의 Refresh Token 백업을 폐기한다. 실패하면(락 경합, 커넥션 순단 등) 최대 3회까지
     * Full Jitter 지수 백오프를 두고 재시도한다. revokeRefreshToken()은 멱등적이라
     * 재시도 자체가 부작용을 만들지 않는다.
     * 3회 다 실패하거나 재시도 예산이 소진되면 null을 반환한다 — 호출자가 아웃박스에 기록해야 한다는 신호다.
     */
    AdminLogoutTransactionService.LogoutDbState revokeDbWithRetry(Long adminId) {
        DataAccessException lastFailure = null;
        long deadlineNanos = retryPolicy.deadline(RETRY_BUDGET);

        for (int attempt = 1; attempt <= MAX_DB_REVOKE_ATTEMPTS; attempt++) {
            try {
                return adminLogoutTransactionService.revokeRefreshToken(adminId);
            } catch (TransientDataAccessException | DataAccessResourceFailureException e) {
                lastFailure = e;
                log.warn("event=ADMIN_LOGOUT_DB_REVOKE_RETRY adminId={} attempt={} errorType={}",
                        adminId, attempt, SafeExceptionLog.errorType(e));
            } catch (DataAccessException e) {
                log.error("event=ADMIN_LOGOUT_DB_REVOKE_NON_RETRYABLE adminId={} errorType={}",
                        adminId, SafeExceptionLog.errorType(e));
                return null;
            }

            if (attempt < MAX_DB_REVOKE_ATTEMPTS
                    && !retryPolicy.waitBeforeRetry(
                    attempt, deadlineNanos, RETRY_BASE_DELAY, RETRY_MAX_DELAY)) {
                break;
            }
        }

        log.error("event=ADMIN_LOGOUT_DB_REVOKE_GAVE_UP adminId={} attempts={} errorType={}",
                adminId, MAX_DB_REVOKE_ATTEMPTS, SafeExceptionLog.errorType(lastFailure));
        return null;
    }

    /**
     * 아웃박스의 지연 재시도 전용 DB 폐기. 실패 당시 해시와 현재 DB 해시가 같을 때만 지운다.
     * 재로그인으로 새 해시가 저장된 경우 조건부 UPDATE가 0건으로 끝나므로 새 RT를 보호한다.
     */
    boolean revokeDbIfMatchesWithRetry(Long adminId, String expectedRefreshTokenHash) {
        DataAccessException lastFailure = null;
        long deadlineNanos = retryPolicy.deadline(RETRY_BUDGET);

        for (int attempt = 1; attempt <= MAX_DB_REVOKE_ATTEMPTS; attempt++) {
            try {
                adminLogoutTransactionService.revokeRefreshTokenIfMatches(adminId, expectedRefreshTokenHash);
                return true;
            } catch (TransientDataAccessException | DataAccessResourceFailureException e) {
                lastFailure = e;
                log.warn("event=ADMIN_LOGOUT_DB_REVOKE_IF_MATCHES_RETRY adminId={} attempt={} errorType={}",
                        adminId, attempt, SafeExceptionLog.errorType(e));
            } catch (DataAccessException e) {
                log.error("event=ADMIN_LOGOUT_DB_REVOKE_IF_MATCHES_NON_RETRYABLE adminId={} errorType={}",
                        adminId, SafeExceptionLog.errorType(e));
                return false;
            }

            if (attempt < MAX_DB_REVOKE_ATTEMPTS
                    && !retryPolicy.waitBeforeRetry(
                    attempt, deadlineNanos, RETRY_BASE_DELAY, RETRY_MAX_DELAY)) {
                break;
            }
        }

        log.error("event=ADMIN_LOGOUT_DB_REVOKE_IF_MATCHES_GAVE_UP adminId={} attempts={} errorType={}",
                adminId, MAX_DB_REVOKE_ATTEMPTS, SafeExceptionLog.errorType(lastFailure));
        return false;
    }

    /*
     * Refresh Token의 Redis 기본 레코드와 active key를 정리한다.
     * 반드시 실패 당시 tokenHash가 있어야 공용 revokeIfActiveHashMatches()의 조건부 Lua로 안전하게 지울 수 있다.
     * tokenHash가 없으면 현재 active key가 과거 로그아웃 대상인지 재로그인 뒤 새 세션인지 구분할 수 없으므로
     * active key를 무조건 삭제하지 않고 false를 반환해 실패 기록을 남긴다.
     */
    boolean cleanupRedisWithRetry(String role, Long adminId, String tokenHash) {
        if (tokenHash == null) {
            log.warn("event=ADMIN_REFRESH_TOKEN_CLEANUP_SKIPPED_MISSING_HASH "
                    + LOG_FIELDS_ROLE_ADMIN_ID, role, adminId);
            return false;
        }

        long deadlineNanos = retryPolicy.deadline(RETRY_BUDGET);
        for (int attempt = 1; attempt <= MAX_REDIS_CLEANUP_ATTEMPTS; attempt++) {
            if (cleanupRefreshTokenOnce(role, adminId, tokenHash, deadlineNanos)) {
                return true;
            }
            log.warn("event=ADMIN_REFRESH_TOKEN_CLEANUP_RETRY " + LOG_FIELDS_ROLE_ADMIN_ID + " attempt={}",
                    role, adminId, attempt);

            if (attempt < MAX_REDIS_CLEANUP_ATTEMPTS
                    && !retryPolicy.waitBeforeRetry(
                    attempt, deadlineNanos, RETRY_BASE_DELAY, RETRY_MAX_DELAY)) {
                break;
            }
        }
        return false;
    }

    /*
     * Refresh Token의 Redis 기본 레코드와 active key를 한 번(내부적으로 "삭제 -> 확인 -> 필요 시
     * 1회 재시도 -> 최종 확인"까지 포함) 정리한다.
     *
     * 회원 쪽과 공유하는 원자적 revoke Lua를 사용해 기본 레코드 삭제와
     * "active key가 아직 같은 해시일 때만 삭제"를 한 번에 처리한다.
     */
    private boolean cleanupRefreshTokenOnce(
            String role, Long adminId, String tokenHash, long deadlineNanos) {
        RedisMutationOutcome revokeOutcome = deleteRedisEntryWithConfirmation(
                "recordAndActiveKey", role, adminId,
                () -> refreshTokenRepository.revokeIfActiveHashMatches(tokenHash, role, adminId),
                () -> checkRefreshTokenRevoked(tokenHash, role, adminId),
                deadlineNanos);

        logCleanupOutcome("RECORD_AND_ACTIVE_KEY", revokeOutcome, role, adminId);
        return revokeOutcome == RedisMutationOutcome.CONFIRMED;
    }

    /*
     * Refresh Token 기본 레코드와 active key의 삭제/확인/재시도 흐름을 공통으로 처리한다.
     * timeout이나 연결 단절이 발생하면 Redis가 실제 작업을 수행했는지 알 수 없으므로 후속 조회로 확인한다.
     * 한 번 더 삭제해야 할 때도 즉시 반복하지 않고 Full Jitter 백오프와 같은 요청 예산을 적용한다.
     */
    private RedisMutationOutcome deleteRedisEntryWithConfirmation(
            String target, String role, Long adminId,
            Runnable deleteAction, Supplier<RedisDeletionState> checkAction,
            long deadlineNanos) {

        // 1. 최초 삭제 시도
        try {
            deleteAction.run();
            return RedisMutationOutcome.CONFIRMED;

        } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_UNKNOWN " + LOG_FIELDS_TARGET_ROLE_ADMIN_ID
                            + " errorType={}",
                    target, role, adminId, SafeExceptionLog.errorType(e));

        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_FAILED " + LOG_FIELDS_TARGET_ROLE_ADMIN_ID
                            + " errorType={}",
                    target, role, adminId, SafeExceptionLog.errorType(e));
            return RedisMutationOutcome.FAILED;
        }

        // 2. timeout/연결 단절로 결과를 알 수 없다면 실제 Redis 상태를 다시 조회한다.
        RedisDeletionState state = checkAction.get();
        if (state == RedisDeletionState.DELETED) {
            return RedisMutationOutcome.CONFIRMED;
        }

        // 3. 아직 키가 남아 있거나 조회 결과가 미확정이면, 예산 안에서 백오프 후 한 번 더 삭제한다.
        if (!retryPolicy.waitBeforeRetry(1, deadlineNanos, RETRY_BASE_DELAY, RETRY_MAX_DELAY)) {
            return state == RedisDeletionState.PRESENT
                    ? RedisMutationOutcome.FAILED
                    : RedisMutationOutcome.UNKNOWN;
        }

        try {
            deleteAction.run();

        } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_RETRY_UNKNOWN " + LOG_FIELDS_TARGET_ROLE_ADMIN_ID
                            + " errorType={}",
                    target, role, adminId, SafeExceptionLog.errorType(e));

        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_RETRY_FAILED " + LOG_FIELDS_TARGET_ROLE_ADMIN_ID
                            + " errorType={}",
                    target, role, adminId, SafeExceptionLog.errorType(e));
            return RedisMutationOutcome.FAILED;
        }

        // 4. 재시도 후 최종 상태를 확인한다.
        state = checkAction.get();

        return switch (state) {
            case DELETED -> RedisMutationOutcome.CONFIRMED;
            case PRESENT -> RedisMutationOutcome.FAILED;
            case UNKNOWN -> RedisMutationOutcome.UNKNOWN;
        };
    }

    private RedisDeletionState checkRefreshTokenRevoked(String tokenHash, String role, Long adminId) {
        try {
            if (refreshTokenRepository.existsByHash(tokenHash)) {
                return RedisDeletionState.PRESENT;
            }

            Optional<String> activeHash = refreshTokenRepository.findActiveHash(role, adminId);
            return activeHash.filter(tokenHash::equals).isPresent()
                    ? RedisDeletionState.PRESENT
                    : RedisDeletionState.DELETED;

        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_CONFIRM_FAILED "
                            + "target=recordAndActiveKey role={} adminId={} errorType={}",
                    role, adminId, SafeExceptionLog.errorType(e));
            return RedisDeletionState.UNKNOWN;
        }
    }

    private void logCleanupOutcome(String target, RedisMutationOutcome outcome, String role, Long adminId) {
        if (outcome != RedisMutationOutcome.CONFIRMED) {
            log.warn("event=ADMIN_REFRESH_TOKEN_{}_CLEANUP_{} " + LOG_FIELDS_ROLE_ADMIN_ID,
                    target, outcome, role, adminId);
        }
    }


    // Redis 변경 작업 자체의 최종 결과.
    private enum RedisMutationOutcome {
        CONFIRMED,
        FAILED,
        UNKNOWN
    }

    /*
     * Redis에서 삭제 대상의 현재 상태.
     * 기존 Boolean의 true / false / null을 대신해 각 상태의 의미를 명확하게 나타낸다.
     */
    private enum RedisDeletionState {
        DELETED,
        PRESENT,
        UNKNOWN
    }
}
