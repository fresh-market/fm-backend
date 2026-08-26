package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.exception.AdminTokenErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.auth.opaque.OpaqueTokenGenerator;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.opaque.TokenHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 관리자 Refresh Token 재발급 오케스트레이션.
 * Redis 정상 시 공용 Lua CAS를 먼저 사용하되, DB의 현재 Refresh Token 상태를 최종 기준으로 다시 검증한다.
 * Redis 장애 시에는 로그인 단계에서 남겨둔 DB 해시 백업으로 역조회해 DB CAS로 회전을 계속한다.
 */
@Slf4j
@Service
public class AdminTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AdminTokenTransactionService transactionService;
    private final Clock clock;
    private final long refreshTokenValiditySeconds;

    public AdminTokenService(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            AdminTokenTransactionService transactionService,
            Clock clock,
            @Value("${ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS:86400}") long refreshTokenValiditySeconds) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.transactionService = transactionService;
        this.clock = clock;
        this.refreshTokenValiditySeconds = refreshTokenValiditySeconds;
    }

    public ReissueResult reissue(String oldRefreshToken) {
        if (oldRefreshToken == null || oldRefreshToken.isBlank()) {
            throw invalidRefreshToken();
        }

        String newRefreshToken = OpaqueTokenGenerator.generate();
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        String newHash = TokenHasher.sha256(newRefreshToken);
        Duration ttl = Duration.ofSeconds(refreshTokenValiditySeconds);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime newExpiresAt = now.plus(ttl);

        RefreshTokenRepository.RotateOutcome outcome;
        try {
            // 관리자 엔드포인트가 회원 Refresh Token을 회전시키지 않도록
            // Redis 레코드의 type을 먼저 확인한 뒤 실제 Rotation을 수행한다.
            refreshTokenRepository.find(oldRefreshToken)
                    .filter(data -> data.type() != TokenType.ADMIN)
                    .ifPresent(data -> {
                        log.warn("event=ADMIN_REFRESH_TOKEN_TYPE_MISMATCH actualType={}", data.type());
                        throw invalidRefreshToken();
                    });

            outcome = refreshTokenRepository.compareAndRotate(oldRefreshToken, newRefreshToken, ttl);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_REDIS_CAS_FAILED — DB fallback으로 재발급 시도", e);
            return reissueViaDbFallback(newRefreshToken, oldHash, newHash, newExpiresAt, now, ttl);
        }

        if (!outcome.isSuccess()) {
            if (outcome.isReuseDetected()) {
                log.warn("event=ADMIN_REFRESH_TOKEN_REUSE_DETECTED");
            }
            throw invalidRefreshToken();
        }

        RefreshTokenRepository.RefreshTokenData rotated = outcome.data();
        if (rotated.type() != TokenType.ADMIN) {
            log.warn("event=ADMIN_REFRESH_TOKEN_TYPE_MISMATCH actualType={}", rotated.type());
            throw invalidRefreshToken();
        }

        AdminTokenTransactionService.RotationState state;
        try {
            state = transactionService.rotateKnownAdmin(
                    rotated.memberId(), oldHash, newHash, newExpiresAt, now);
        } catch (RuntimeException e) {
            // Redis Rotation은 이미 끝났는데 DB가 최종 검증/CAS에 실패한 경우
            // 방금 만들어진 신규 Redis RT 기본 레코드를 제거해 유효한 고아 토큰이 남지 않게 한다.
            compensateRedisRotation(newHash);
            throw e;
        }

        return issueResult(state, newRefreshToken);
    }

    private ReissueResult reissueViaDbFallback(
            String newRefreshToken,
            String oldHash,
            String newHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now,
            Duration ttl) {
        AdminTokenTransactionService.RotationState state = transactionService.rotateByHash(
                oldHash, newHash, newExpiresAt, now);
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
            // DB CAS가 이미 성공했으므로 Redis 장애가 계속되어도 DB를 최종 근거로 재발급은 성공시킨다.
            log.warn("event=ADMIN_REFRESH_REDIS_SAVE_FAILED adminId={} — DB 상태로 재발급 유지",
                    state.adminId(), e);
        }

        return issueResult(state, newRefreshToken);
    }

    private void compensateRedisRotation(String newHash) {
        try {
            refreshTokenRepository.deleteByHash(newHash);
        } catch (DataAccessException cleanupFailure) {
            log.warn(
                    "event=ADMIN_REFRESH_REDIS_COMPENSATION_FAILED",
                    cleanupFailure
            );
        }
    }

    private ReissueResult issueResult(
            AdminTokenTransactionService.RotationState state,
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