package com.freshmarket.admin.domain.service;

import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
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
 * DB 폐기(revokeDbWithRetry)와 Redis 정리(cleanupRedisWithRetry)는 서로 독립적으로 3회씩
 * 재시도한다. 재시도 사이에 대기(sleep)를 주지 않는다 — 이 메서드들은 로그아웃 요청을 처리
 * 중인 스레드에서도 불리므로, 여기서 자면 그만큼 다른 요청을 못 받는다
 * (KakaoUnlinkEventListener와 같은 이유).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AdminRefreshTokenCleanupService {

    private static final int MAX_DB_REVOKE_ATTEMPTS = 3;
    private static final int MAX_REDIS_CLEANUP_ATTEMPTS = 3;

    // Redis 정리/차단 로그에서 반복되는 필드 포맷
    private static final String LOG_FIELDS_ROLE_ADMIN_ID = "role={} adminId={}";
    private static final String LOG_FIELDS_TARGET_ROLE_ADMIN_ID = "target={} role={} adminId={}";

    private final RefreshTokenRepository refreshTokenRepository;
    private final AdminLogoutTransactionService adminLogoutTransactionService;

    /*
     * DB의 Refresh Token 백업을 폐기한다. 실패하면(락 경합, 커넥션 순단 등) 최대 3회까지
     * 즉시 재시도한다 — revokeRefreshToken()은 멱등적이라(이미 폐기된 상태에 다시 호출해도
     * null -> null) 재시도 자체가 부작용을 만들지 않는다.
     * 3회 다 실패하면 null을 반환한다 — 호출자가 아웃박스에 기록해야 한다는 신호다.
     */
    AdminLogoutTransactionService.LogoutDbState revokeDbWithRetry(Long adminId) {
        DataAccessException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_DB_REVOKE_ATTEMPTS; attempt++) {
            try {
                return adminLogoutTransactionService.revokeRefreshToken(adminId);
            } catch (DataAccessException e) {
                lastFailure = e;
                log.warn("event=ADMIN_LOGOUT_DB_REVOKE_RETRY adminId={} attempt={}", adminId, attempt, e);
            }
        }

        log.error("event=ADMIN_LOGOUT_DB_REVOKE_GAVE_UP adminId={} attempts={}",
                adminId, MAX_DB_REVOKE_ATTEMPTS, lastFailure);
        return null;
    }

    /*
     * Refresh Token의 Redis 기본 레코드(tokenHash가 있을 때만)와 active key를 정리한다.
     * 둘 다 확정(CONFIRMED)돼야 true를 반환한다 — 하나라도 아니면 전체를 다시 시도할 대상이다.
     * 최대 3회까지 전체를 다시 시도한다. Redis DELETE는 멱등적이라 이미 지워진 걸 다시 지워도
     * 결과는 같으므로, 부분적으로 성공한 부분을 다시 시도해도 문제없다.
     */
    boolean cleanupRedisWithRetry(String role, Long adminId, String tokenHash) {
        for (int attempt = 1; attempt <= MAX_REDIS_CLEANUP_ATTEMPTS; attempt++) {
            if (cleanupRefreshTokenOnce(role, adminId, tokenHash)) {
                return true;
            }
            log.warn("event=ADMIN_REFRESH_TOKEN_CLEANUP_RETRY " + LOG_FIELDS_ROLE_ADMIN_ID + " attempt={}",
                    role, adminId, attempt);
        }
        return false;
    }

    /*
     * Refresh Token의 Redis 기본 레코드와 active key를 한 번(내부적으로 "삭제 -> 확인 -> 필요 시
     * 1회 재시도 -> 최종 확인"까지 포함) 정리한다. 두 대상 다 확정돼야 true를 반환한다.
     */
    private boolean cleanupRefreshTokenOnce(String role, Long adminId, String tokenHash) {
        boolean primaryOk = true;

        if (tokenHash != null) {
            RedisMutationOutcome primaryOutcome = deleteRedisEntryWithConfirmation(
                    "record", role, adminId,
                    () -> refreshTokenRepository.deleteByHash(tokenHash),
                    () -> checkRefreshTokenRecordDeleted(tokenHash));

            logCleanupOutcome("RECORD", primaryOutcome, role, adminId);
            primaryOk = primaryOutcome == RedisMutationOutcome.CONFIRMED;
        }

        RedisMutationOutcome activeKeyOutcome = deleteRedisEntryWithConfirmation(
                "activeKey", role, adminId,
                () -> refreshTokenRepository.deleteActiveKey(role, adminId),
                () -> checkRefreshTokenActiveKeyDeleted(role, adminId));

        logCleanupOutcome("ACTIVE_KEY", activeKeyOutcome, role, adminId);
        boolean activeKeyOk = activeKeyOutcome == RedisMutationOutcome.CONFIRMED;

        return primaryOk && activeKeyOk;
    }

    /*
     * Refresh Token 기본 레코드와 active key의 삭제/확인/재시도 흐름을 공통으로 처리한다.
     * timeout이나 연결 단절이 발생하면 Redis가 실제 작업을 수행했는지 알 수 없으므로 즉시
     * 실패라고 판단하지 않고 후속 조회로 확인한다. 반면 timeout이 아닌 확정적인
     * DataAccessException은 재시도하지 않는다 — 같은 이유로 또 실패할 뿐이다.
     */
    private RedisMutationOutcome deleteRedisEntryWithConfirmation(
            String target, String role, Long adminId,
            Runnable deleteAction, Supplier<RedisDeletionState> checkAction) {

        // 1. 최초 삭제 시도
        try {
            deleteAction.run();
            return RedisMutationOutcome.CONFIRMED;

        } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_UNKNOWN " + LOG_FIELDS_TARGET_ROLE_ADMIN_ID,
                    target, role, adminId, e);

        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_FAILED " + LOG_FIELDS_TARGET_ROLE_ADMIN_ID,
                    target, role, adminId, e);
            return RedisMutationOutcome.FAILED;
        }

        // 2. timeout/연결 단절로 결과를 알 수 없다면 실제 Redis 상태를 다시 조회한다.
        RedisDeletionState state = checkAction.get();
        if (state == RedisDeletionState.DELETED) {
            return RedisMutationOutcome.CONFIRMED;
        }

        // 3. 아직 키가 남아 있거나, 조회 결과 자체를 확인할 수 없으면 한 번 더 삭제한다.
        try {
            deleteAction.run();

        } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_RETRY_UNKNOWN " + LOG_FIELDS_TARGET_ROLE_ADMIN_ID,
                    target, role, adminId, e);

        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_RETRY_FAILED " + LOG_FIELDS_TARGET_ROLE_ADMIN_ID,
                    target, role, adminId, e);
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

    private RedisDeletionState checkRefreshTokenRecordDeleted(String tokenHash) {
        try {
            return refreshTokenRepository.existsByHash(tokenHash)
                    ? RedisDeletionState.PRESENT
                    : RedisDeletionState.DELETED;

        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_CONFIRM_FAILED target=record", e);
            return RedisDeletionState.UNKNOWN;
        }
    }

    private RedisDeletionState checkRefreshTokenActiveKeyDeleted(String role, Long adminId) {
        try {
            return refreshTokenRepository.findActiveHash(role, adminId).isEmpty()
                    ? RedisDeletionState.DELETED
                    : RedisDeletionState.PRESENT;

        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_CONFIRM_FAILED target=activeKey role={} adminId={}",
                    role, adminId, e);
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