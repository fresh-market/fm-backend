package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.exception.AdminTokenErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminTokenRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.auth.opaque.OpaqueTokenGenerator;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.opaque.TokenHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;

/**
 * 관리자 Refresh Token 재발급 오케스트레이션.
 * Redis 정상 시 공용 Lua CAS를 사용하되, DB의 현재 Refresh Token 상태를 최종 기준으로 다시 검증한다.
 * Redis 장애 시에는 로그인 단계에서 남겨둔 DB 해시 백업으로 회전을 계속한다.
 * Redis 회전 명령의 timeout은 결과를 모르는 상태이므로 DB fallback과 구분하고 후속 조회로 확정한다.
 */
@Slf4j
@Service
public class AdminTokenService {

    private static final int MAX_REFRESH_TOKEN_LENGTH = 512;

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AdminTokenRepository adminTokenRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final Clock clock;
    private final long refreshTokenValiditySeconds;

    public AdminTokenService(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            AdminTokenRepository adminTokenRepository,
            AdminAuditLogRepository adminAuditLogRepository,
            Clock clock,
            @Value("${ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS:86400}") long refreshTokenValiditySeconds) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.adminTokenRepository = adminTokenRepository;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.clock = clock;
        this.refreshTokenValiditySeconds = refreshTokenValiditySeconds;
    }

    public ReissueResult reissue(String oldRefreshToken) {
        validateRefreshToken(oldRefreshToken);

        String newRefreshToken = OpaqueTokenGenerator.generate();
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        String newHash = TokenHasher.sha256(newRefreshToken);
        Duration ttl = Duration.ofSeconds(refreshTokenValiditySeconds);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime newExpiresAt = now.plus(ttl);

        Optional<RefreshTokenRepository.RefreshTokenData> current;
        try {
            // 조회 단계에서는 Redis 상태를 바꾸지 않으므로 실패해도 DB fallback이 안전하다.
            current = refreshTokenRepository.find(oldRefreshToken);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_REDIS_LOOKUP_FAILED — DB fallback으로 재발급 시도", e);
            return reissueViaDbFallback(newRefreshToken, oldHash, newHash, newExpiresAt, now, ttl, null);
        }

        // Redis가 살아 있어도 재시작/축출로 RT 레코드만 유실될 수 있다.
        // 이 경우 로그인 단계에서 남겨둔 DB 해시 백업을 최종 근거로 재발급을 계속한다.
        if (current.isEmpty()) {
            return reissueViaDbFallback(
                    newRefreshToken, oldHash, newHash, newExpiresAt, now, ttl, null);
        }

        current.filter(data -> data.type() != TokenType.ADMIN)
                .ifPresent(data -> {
                    log.warn("event=ADMIN_REFRESH_TOKEN_TYPE_MISMATCH actualType={}", data.type());
                    throw invalidRefreshToken();
                });
        Long adminIdHint = current
                .filter(data -> data.type() == TokenType.ADMIN)
                .map(RefreshTokenRepository.RefreshTokenData::memberId)
                .orElse(null);

        RefreshTokenRepository.RotateOutcome outcome;
        try {
            outcome = refreshTokenRepository.compareAndRotate(oldRefreshToken, newRefreshToken, ttl);
        } catch (DataAccessException e) {
            if (isTimeout(e)) {
                return resolveRedisRotationTimeout(
                        newRefreshToken, oldHash, newHash, newExpiresAt, now, adminIdHint);
            }
            log.warn("event=ADMIN_REFRESH_REDIS_CAS_FAILED — DB fallback으로 재발급 시도", e);
            return reissueViaDbFallback(
                    newRefreshToken, oldHash, newHash, newExpiresAt, now, ttl, adminIdHint);
        }

        RefreshTokenRepository.RefreshTokenData rotated = requireSuccessfulAdminRotation(outcome);

        AdminTokenRepository.RotationState state;
        try {
            state = adminTokenRepository.rotateKnownAdmin(rotated.memberId(), oldHash, newHash, newExpiresAt, now);
        } catch (RuntimeException e) {
            compensateRedisRotation(rotated.role(), rotated.memberId(), newHash);
            recordFailureAuditSafely(rotated.memberId(), "DB_VALIDATION_OR_CAS_FAILED");
            throw e;
        }

        return issueResult(state, newRefreshToken);
    }

    private ReissueResult resolveRedisRotationTimeout(
            String newRefreshToken,
            String oldHash,
            String newHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now,
            Long adminIdHint) {
        try {
            Optional<RefreshTokenRepository.RefreshTokenData> confirmed =
                    refreshTokenRepository.find(newRefreshToken);
            if (confirmed.isEmpty()) {
                recordFailureAuditSafely(adminIdHint, "REDIS_TIMEOUT_RESULT_UNKNOWN");
                throw resultUnknown();
            }

            RefreshTokenRepository.RefreshTokenData data = confirmed.get();
            if (data.type() != TokenType.ADMIN) {
                recordFailureAuditSafely(adminIdHint, "REDIS_TIMEOUT_TYPE_MISMATCH");
                throw resultUnknown();
            }

            return finalizeRotationAfterRedisTimeout(
                    data, oldHash, newHash, newExpiresAt, now, newRefreshToken);
        } catch (DataAccessException confirmationFailure) {
            recordFailureAuditSafely(adminIdHint, "REDIS_TIMEOUT_CONFIRMATION_FAILED");
            throw resultUnknown(confirmationFailure);
        }
    }

    private RefreshTokenRepository.RefreshTokenData requireSuccessfulAdminRotation(
            RefreshTokenRepository.RotateOutcome outcome) {
        if (!outcome.isSuccess()) {
            if (outcome.isReuseDetected()) {
                RefreshTokenRepository.RefreshTokenData reused = outcome.data();
                log.warn("event=ADMIN_REFRESH_TOKEN_REUSE_DETECTED");
                if (reused != null && reused.type() == TokenType.ADMIN) {
                    recordFailureAuditSafely(reused.memberId(), "REUSE_DETECTED");
                }
            }
            throw invalidRefreshToken();
        }

        RefreshTokenRepository.RefreshTokenData rotated = outcome.data();
        if (rotated.type() != TokenType.ADMIN) {
            log.warn("event=ADMIN_REFRESH_TOKEN_TYPE_MISMATCH actualType={}", rotated.type());
            throw invalidRefreshToken();
        }
        return rotated;
    }

    private ReissueResult finalizeRotationAfterRedisTimeout(
            RefreshTokenRepository.RefreshTokenData data,
            String oldHash,
            String newHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now,
            String newRefreshToken) {
        try {
            AdminTokenRepository.RotationState state = adminTokenRepository.rotateKnownAdmin(
                    data.memberId(), oldHash, newHash, newExpiresAt, now);
            return issueResult(state, newRefreshToken);
        } catch (RuntimeException e) {
            compensateRedisRotation(data.role(), data.memberId(), newHash);
            recordFailureAuditSafely(data.memberId(), "DB_VALIDATION_AFTER_REDIS_TIMEOUT_FAILED");
            throw e;
        }
    }

    private ReissueResult reissueViaDbFallback(
            String newRefreshToken,
            String oldHash,
            String newHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now,
            Duration ttl,
            Long adminIdHint) {
        Long auditAdminId = adminIdHint;
        if (auditAdminId == null) {
            try {
                // 재발급 성공 판정용 조회가 아니라, DB fallback 실패 시 감사 주체를 남기기 위한 힌트다.
                // 실제 유효성/동시성 판정은 아래 rotateByHash()의 DB 검증 + CAS가 최종 결정한다.
                auditAdminId = adminTokenRepository.findAdminIdByRefreshTokenHash(oldHash).orElse(null);
            } catch (DataAccessException lookupFailure) {
                log.warn("event=ADMIN_REFRESH_DB_AUDIT_SUBJECT_LOOKUP_FAILED", lookupFailure);
            }
        }

        final AdminTokenRepository.RotationState state;
        try {
            state = adminTokenRepository.rotateByHash(oldHash, newHash, newExpiresAt, now);
        } catch (RuntimeException e) {
            recordFailureAuditSafely(auditAdminId, "DB_FALLBACK_FAILED");
            throw e;
        }
        String role = state.role().toAuthority();

        try {
            refreshTokenRepository.save(
                    newRefreshToken,
                    state.adminId(),
                    role,
                    TokenType.ADMIN,
                    false,
                    ttl);
        } catch (DataAccessException e) {
            // DB CAS와 성공 감사 로그가 이미 확정됐으므로 Redis 장애가 계속되어도 DB 상태로 재발급을 유지한다.
            log.warn("event=ADMIN_REFRESH_REDIS_SAVE_FAILED adminId={} — DB 상태로 재발급 유지",
                    state.adminId(), e);
        }

        return issueResult(state, newRefreshToken);
    }

    private void validateRefreshToken(String refreshToken) {
        if (refreshToken == null
                || refreshToken.isBlank()
                || refreshToken.length() > MAX_REFRESH_TOKEN_LENGTH) {
            throw invalidRefreshToken();
        }
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof QueryTimeoutException
                    || current.getClass().getSimpleName().toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void compensateRedisRotation(
            String role,
            Long adminId,
            String newHash) {

        try {
            // 1. 방금 Rotation으로 생성된 기본 RT 레코드 제거
            refreshTokenRepository.deleteByHash(newHash);

            // 2. activeKey가 아직 방금 생성한 newHash를 가리킬 때만 제거
            // 그 사이 더 최신 로그인/재발급이 있었다면 최신 activeKey는 건드리지 않는다.
            refreshTokenRepository.deleteActiveKeyIfMatches(role, adminId, newHash);

        } catch (DataAccessException cleanupFailure) {
            log.warn("event=ADMIN_REFRESH_REDIS_COMPENSATION_FAILED adminId={}", adminId, cleanupFailure);
        }
    }

    private void recordFailureAuditSafely(Long adminId, String reason) {
        if (adminId == null) {
            return;
        }
        try {
            adminAuditLogRepository.save(AdminAuditLog.of(
                    adminId,
                    "ADMIN_TOKEN_REISSUE",
                    String.valueOf(adminId),
                    "result=FAILURE;reason=" + reason));
        } catch (DataAccessException auditFailure) {
            // 실패 감사 로그 저장 자체의 장애가 원래 재발급 실패 원인을 덮어쓰지 않게 한다.
            log.error("event=ADMIN_TOKEN_REISSUE_AUDIT_FAILED adminId={}", adminId, auditFailure);
        }
    }

    private ReissueResult issueResult(
            AdminTokenRepository.RotationState state,
            String refreshToken) {
        String accessToken = jwtTokenProvider.createAccessToken(
                state.adminId(), TokenType.ADMIN, state.role().toAuthority());
        return new ReissueResult(
                accessToken,
                jwtTokenProvider.getAccessTokenValidityMs() / 1000,
                refreshToken,
                refreshTokenValiditySeconds);
    }

    private AdminException invalidRefreshToken() {
        return new AdminException(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);
    }

    private AdminException resultUnknown() {
        return new AdminException(AdminTokenErrorCode.REFRESH_TOKEN_RESULT_UNKNOWN);
    }

    private AdminException resultUnknown(Throwable cause) {
        return new AdminException(AdminTokenErrorCode.REFRESH_TOKEN_RESULT_UNKNOWN, cause);
    }

    public record ReissueResult(
            String accessToken,
            long expiresInSeconds,
            String refreshToken,
            long refreshTokenValiditySeconds) {

        @Override
        public String toString() {
            return "ReissueResult[accessToken=****, expiresInSeconds=" + expiresInSeconds
                    + ", refreshToken=****, refreshTokenValiditySeconds=" + refreshTokenValiditySeconds + "]";
        }
    }
}