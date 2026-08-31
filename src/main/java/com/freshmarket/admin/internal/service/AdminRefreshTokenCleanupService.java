package com.freshmarket.admin.internal.service;

import com.freshmarket.admin.internal.logging.SafeExceptionLog;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/*
 * 관리자 Refresh Token의 DB 폐기와 Redis 정리를 각각 한 번만 수행한다.
 * 서버 내부 재시도, Jitter, 스케줄러 재처리는 사용하지 않는다.
 * 실패하면 로그를 남기고 AdminAuthService가 ADMIN-010 실패 응답으로 확정하며,
 * 이후 재시도는 클라이언트의 새 로그아웃 요청에 맡긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AdminRefreshTokenCleanupService {

    private static final String LOG_FIELDS_ROLE_ADMIN_ID = "role={} adminId={}";

    private final RefreshTokenRepository refreshTokenRepository;
    private final AdminLogoutTransactionService adminLogoutTransactionService;

    /*
     * DB의 Refresh Token 백업을 한 번 폐기한다.
     * DB 작업이 실패하면 서버에서 다시 시도하지 않고 null을 반환한다.
     */
    AdminLogoutTransactionService.LogoutDbState revokeDbOnce(Long adminId) {
        try {
            return adminLogoutTransactionService.revokeRefreshToken(adminId);
        } catch (DataAccessException e) {
            log.error(
                    "event=ADMIN_LOGOUT_DB_REVOKE_FAILED adminId={} errorType={}",
                    adminId,
                    SafeExceptionLog.errorType(e),
                    SafeExceptionLog.stackTrace(e));
            return null;
        }
    }

    /*
     * Refresh Token의 Redis 기본 레코드와 active key를 한 번만 조건부 삭제한다.
     * tokenHash가 없으면 다른 로그인 세션의 active key를 잘못 지울 수 있으므로 삭제하지 않는다.
     */
    boolean cleanupRedisOnce(String role, Long adminId, String tokenHash) {
        if (tokenHash == null) {
            log.warn(
                    "event=ADMIN_REFRESH_TOKEN_CLEANUP_SKIPPED_MISSING_HASH "
                            + LOG_FIELDS_ROLE_ADMIN_ID,
                    role,
                    adminId);
            return false;
        }

        try {
            refreshTokenRepository.revokeIfActiveHashMatches(tokenHash, role, adminId);
            return true;
        } catch (DataAccessException e) {
            log.error(
                    "event=ADMIN_REFRESH_TOKEN_CLEANUP_FAILED role={} adminId={} errorType={}",
                    role,
                    adminId,
                    SafeExceptionLog.errorType(e),
                    SafeExceptionLog.stackTrace(e));
            return false;
        }
    }
}