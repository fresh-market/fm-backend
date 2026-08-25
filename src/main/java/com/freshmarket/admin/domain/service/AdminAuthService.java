package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.dto.AdminLoginRequest;
import com.freshmarket.admin.domain.dto.AdminLoginResponse;
import com.freshmarket.admin.domain.dto.AdminLoginResult;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminRepository;
import com.freshmarket.common.auth.jwt.AccessTokenValidAfterRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.auth.opaque.OpaqueTokenGenerator;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.opaque.TokenHasher;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.freshmarket.common.logging.PiiMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자 로그인과 로그아웃을 다룬다.
 * 토큰 재발급과 비밀번호 변경은 별도 PR이다 (auth.md 참고).
 *
 * (merge: feat/member-auth와 합치며 추가) JWT 서명·액세스 토큰 발급은 member/admin이 공유하는
 * common.auth.jwt.JwtTokenProvider를 그대로 쓴다.
 * 액세스 토큰 유효기간도 JwtTokenProvider가 관리한다.
 * 리프레시 토큰은 member와 같은 공통 RefreshTokenRepository(Redis)에 저장한다.
 * 로그인은 최초 발급만 담당하고, Rotation은 별도 재발급 API에서 처리한다.
 */
@Slf4j
@Service
public class AdminAuthService {

    // 실제 계정과 무관한 값이다. 계정이 없을 때도 이 해시로 BCrypt 를 돌려 응답 시간을 맞춘다 (SEC-6-04)
    private static final String DUMMY_PASSWORD_SOURCE = "dummy-password-for-constant-time-comparison";

    // Access Token 차단 커트라인 반영 여부를 확인할 때 사용하는 최소 시간 오프셋
    private static final long CUTOFF_CONFIRMATION_OFFSET_NANOS = 1L;

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshTokenValiditySeconds;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final AdminLogoutTransactionService adminLogoutTransactionService;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminAuthService(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            AccessTokenValidAfterRepository accessTokenValidAfterRepository,
            AdminLogoutTransactionService adminLogoutTransactionService,
            Clock clock,
            @Value("${ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS:86400}") long refreshTokenValiditySeconds) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTokenValidAfterRepository = accessTokenValidAfterRepository;
        this.adminLogoutTransactionService = adminLogoutTransactionService;
        this.clock = clock;
        this.refreshTokenValiditySeconds = refreshTokenValiditySeconds;
        // 같은 인코더로 미리 만들어 둬야 진짜 비밀번호 검증과 연산 비용(코스트 팩터)이 완전히 같다
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD_SOURCE);
    }

    @Transactional(timeout = 5)
    public AdminLoginResult login(AdminLoginRequest request) {
        Objects.requireNonNull(request, "request");

        Optional<Admin> found = adminRepository.findByLoginId(request.loginId());

        /*
         * 계정이 없어도 항상 BCrypt 를 돌린다 (SEC-6-04, auth.md "관리자 > 로그인" 절).
         * 계정이 없을 때 BCrypt 자체를 건너뛰면, 있을 때와 없을 때의 응답 시간이 갈려서
         * 그 시간 차이가 그 자체로 아이디 존재 여부를 흘리는 타이밍 사이드채널이 된다.
         *
         * found 가 비어 있으면 아래 단락 값과 무관하게 항상 LOGIN_FAILED 로 던진다
         * (단락 평가로 그렇게 되어 있다). dummyPasswordHash 비교는 오직 시간을 맞추기 위한 것이다.
         */
        String hashToCompare = found.map(Admin::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCompare);

        if (found.isEmpty() || !passwordMatches) {
            log.warn("event=ADMIN_LOGIN success=false loginId={}", maskLoginId(request.loginId()));
            throw new AdminException(AdminErrorCode.LOGIN_FAILED);
        }

        /*
         * 비활성 계정도 외부에는 일반 로그인 실패와 같은 응답으로 처리한다 (SEC-6-04).
         * 아이디/비밀번호가 맞더라도 계정 상태를 별도 코드로 알려주면 공격자가 계정 상태를 추측할 수 있으므로 LOGIN_FAILED 로 통일한다.
         */
        Admin admin = found.get();
        if (!admin.isActive()) {
            log.warn("event=ADMIN_LOGIN success=false loginId={}", maskLoginId(request.loginId()));
            throw new AdminException(AdminErrorCode.LOGIN_FAILED);
        }

        /*
         * 비밀번호 검증이 끝난 성공 후보에 대해서만 관리자 행을 잠근다.
         * findByLoginId 자체에 PESSIMISTIC_WRITE를 걸면 존재하는 계정에 대한 비밀번호 실패 요청도
         * 느린 BCrypt 비교 동안 쓰기 잠금을 점유하므로, 실제 Refresh Token 상태를 갱신하기 직전에만 잠근다.
         * 잠금을 얻는 사이 계정 상태가 바뀔 수 있으므로 잠금 조회 뒤 활성 상태도 다시 확인한다.
         */
        Admin lockedAdmin = adminRepository.findByIdForUpdate(admin.getId())
                .orElseThrow(() -> new AdminException(AdminErrorCode.LOGIN_FAILED));
        if (!lockedAdmin.isActive()) {
            log.warn("event=ADMIN_LOGIN success=false loginId={}", maskLoginId(request.loginId()));
            throw new AdminException(AdminErrorCode.LOGIN_FAILED);
        }

        String accessToken = jwtTokenProvider.createAccessToken(
                lockedAdmin.getId(), TokenType.ADMIN, lockedAdmin.getRole().toAuthority());

        String rawRefreshToken = OpaqueTokenGenerator.generate();
        Duration refreshTtl = Duration.ofSeconds(refreshTokenValiditySeconds);
        refreshTokenRepository.save(
                rawRefreshToken,
                lockedAdmin.getId(),
                lockedAdmin.getRole().toAuthority(),
                TokenType.ADMIN,
                false,
                refreshTtl);

        /* Redis의 active key가 유실돼도 로그아웃 시 실제 Refresh Token 레코드를 찾을 수 있도록 DB에도 해시와 만료시각을 백업한다.
         * 평문 토큰은 DB에 저장하지 않는다.
         */
        lockedAdmin.issueRefreshToken(
                TokenHasher.sha256(rawRefreshToken),
                LocalDateTime.now(clock).plus(refreshTtl));

        AdminLoginResponse response = new AdminLoginResponse(
                jwtTokenProvider.getAccessTokenValidityMs() / 1000,
                new AdminLoginResponse.AdminSummary(
                        lockedAdmin.getLoginId(), lockedAdmin.getName(), lockedAdmin.getRole()));

        log.info("event=ADMIN_LOGIN success=true adminId={} loginId={}",
                lockedAdmin.getId(), maskLoginId(lockedAdmin.getLoginId()));

        // 두 토큰 원문은 응답 본문이 아니라 컨트롤러가 만드는 HttpOnly 쿠키로만 나간다
        return new AdminLoginResult(response, accessToken, rawRefreshToken, refreshTokenValiditySeconds);
    }

    /*
     * 관리자 로그아웃. DB의 Refresh Token 백업을 먼저 짧은 트랜잭션에서 폐기한 뒤
     * Redis 정리와 Access Token 차단은 트랜잭션 밖에서 수행한다.
     *
     * Refresh Token은 DB를 최종 판정 기준으로 사용한다. Redis 삭제가 실패하거나 결과가
     * 미확정이어도 DB에서 이미 폐기된 토큰은 후속 재발급에서 거부되어야 한다.
     * Access Token은 기존 공용 JwtAuthenticationFilter의 Redis fail-open 정책을 유지하되,
     * 이 로그아웃 요청 자체는 차단 커트라인 저장 결과를 확정하지 못하면 성공으로 응답하지 않는다.
     */
    public void logout(Long adminId, String role) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(role, "role");

        AdminLogoutTransactionService.LogoutDbState dbState =
                adminLogoutTransactionService.revokeRefreshToken(adminId);

        cleanupRefreshToken(role, adminId, dbState.refreshTokenHash());

        LocalDateTime cutoff = LocalDateTime.now(clock);
        invalidateAccessTokenOrThrow(role, adminId, cutoff);

        adminLogoutTransactionService.recordSuccess(adminId);

        /*
         * 이 시점이면 Refresh Token 폐기와 Access Token 차단은 이미 확정된 뒤다.
         * 감사 로그는 그 결과를 기록만 하는 부가 작업이라, 저장이 실패해도 이미 끝난 로그아웃 자체를 실패로 되돌리지 않는다(fail-open).
         * 그러지 않으면 보안적으로 완전히 끝난 로그아웃이 감사 로그 하나 때문에 스펙에 없는 500으로 응답되고, 쿠키도 안 지워져
         * 클라이언트가 이미 무의미해진 재시도를 하게 된다.
         */
        try {
            adminLogoutTransactionService.recordSuccess(adminId);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_LOGOUT_AUDIT_LOG_FAILED adminId={}", adminId, e);
        }

        log.info("event=ADMIN_LOGOUT success=true adminId={}", adminId);
    }

    /*
     * Refresh Token의 Redis 기본 레코드와 active key를 정리한다.
     *
     * 두 대상은 삭제 방법만 다르고 "삭제 → 결과 확인 → 필요 시 재시도 → 최종 확인" 흐름은 동일하므로
     * deleteRedisEntryWithConfirmation()을 공통으로 사용한다.
     */
    private void cleanupRefreshToken(
            String role,
            Long adminId,
            String tokenHash) {

        if (tokenHash != null) {
            RedisMutationOutcome primaryOutcome =
                    deleteRedisEntryWithConfirmation(
                            "record",
                            role,
                            adminId,
                            () ->
                                    refreshTokenRepository
                                            .deleteByHash(tokenHash),
                            () ->
                                    checkRefreshTokenRecordDeleted(
                                            tokenHash));

            logCleanupOutcome(
                    "RECORD",
                    primaryOutcome,
                    role,
                    adminId);
        }

        RedisMutationOutcome activeKeyOutcome =
                deleteRedisEntryWithConfirmation(
                        "activeKey",
                        role,
                        adminId,
                        () ->
                                refreshTokenRepository
                                        .deleteActiveKey(
                                                role,
                                                adminId),
                        () ->
                                checkRefreshTokenActiveKeyDeleted(
                                        role,
                                        adminId));

        logCleanupOutcome(
                "ACTIVE_KEY",
                activeKeyOutcome,
                role,
                adminId);
    }

    /*
     * Refresh Token 기본 레코드와 active key의 삭제/확인/재시도 흐름을 공통으로 처리한다.
     * timeout이나 연결 단절이 발생하면 Redis가 실제 작업을 수행했는지 알 수 없으므로 즉시 실패라고 판단하지 않고 후속 조회로 확인한다.
     */
    private RedisMutationOutcome deleteRedisEntryWithConfirmation(
            String target,
            String role,
            Long adminId,
            Runnable deleteAction,
            Supplier<RedisDeletionState> checkAction) {

        // 1. 최초 삭제 시도
        try {
            deleteAction.run();

            return RedisMutationOutcome.CONFIRMED;

        } catch (
                QueryTimeoutException
                | DataAccessResourceFailureException e) {

            log.warn(
                    "event=ADMIN_REFRESH_TOKEN_DELETE_UNKNOWN "
                            + "target={} role={} adminId={}",
                    target,
                    role,
                    adminId,
                    e);

        } catch (DataAccessException e) {

            log.warn(
                    "event=ADMIN_REFRESH_TOKEN_DELETE_FAILED "
                            + "target={} role={} adminId={}",
                    target,
                    role,
                    adminId,
                    e);

            return RedisMutationOutcome.FAILED;
        }

        // 2. timeout/연결 단절로 결과를 알 수 없다면 실제 Redis 상태를 다시 조회한다.
        RedisDeletionState state = checkAction.get();

        if (state == RedisDeletionState.DELETED) { return RedisMutationOutcome.CONFIRMED; }

        // 3. 아직 키가 남아 있거나, 조회 결과 자체를 확인할 수 없으면 한 번 더 삭제한다.
        // Redis DELETE는 멱등적이므로 이미 삭제된 상태에서 다시 호출돼도 최종 결과는 같다.
        try {
            deleteAction.run();

        } catch (
                QueryTimeoutException
                | DataAccessResourceFailureException e) {

            log.warn(
                    "event=ADMIN_REFRESH_TOKEN_DELETE_RETRY_UNKNOWN "
                            + "target={} role={} adminId={}",
                    target,
                    role,
                    adminId,
                    e);

        } catch (DataAccessException e) {

            log.warn(
                    "event=ADMIN_REFRESH_TOKEN_DELETE_RETRY_FAILED "
                            + "target={} role={} adminId={}",
                    target,
                    role,
                    adminId,
                    e);

            return RedisMutationOutcome.FAILED;
        }

        // 4. 재시도 후 최종 상태를 확인한다.
        state = checkAction.get();

        return switch (state) {
            case DELETED ->
                    RedisMutationOutcome.CONFIRMED;

            case PRESENT ->
                    RedisMutationOutcome.FAILED;

            case UNKNOWN ->
                    RedisMutationOutcome.UNKNOWN;
        };
    }

    /*
     * Refresh Token 기본 레코드의 삭제 여부를 확인한다.
     * Boolean의 true/false/null 대신 DELETED/PRESENT/UNKNOWN으로 상태를 명확하게 표현한다.
     */
    private RedisDeletionState checkRefreshTokenRecordDeleted(
            String tokenHash) {

        try {
            return refreshTokenRepository
                    .existsByHash(tokenHash)
                    ? RedisDeletionState.PRESENT
                    : RedisDeletionState.DELETED;

        } catch (DataAccessException e) {

            log.warn(
                    "event=ADMIN_REFRESH_TOKEN_DELETE_CONFIRM_FAILED "
                            + "target=record",
                    e);

            return RedisDeletionState.UNKNOWN;
        }
    }

    // Refresh Token active key의 삭제 여부를 확인한다.
    private RedisDeletionState checkRefreshTokenActiveKeyDeleted(
            String role,
            Long adminId) {

        try {
            return refreshTokenRepository
                    .findActiveHash(
                            role,
                            adminId)
                    .isEmpty()
                    ? RedisDeletionState.DELETED
                    : RedisDeletionState.PRESENT;

        } catch (DataAccessException e) {

            log.warn(
                    "event=ADMIN_REFRESH_TOKEN_DELETE_CONFIRM_FAILED "
                            + "target=activeKey role={} adminId={}",
                    role,
                    adminId,
                    e);

            return RedisDeletionState.UNKNOWN;
        }
    }

    // 기본 레코드와 active key에서 반복되던 cleanup 결과 로그를 한 곳에서 처리한다.
    private void logCleanupOutcome(
            String target,
            RedisMutationOutcome outcome,
            String role,
            Long adminId) {

        if (outcome != RedisMutationOutcome.CONFIRMED) {
            log.warn(
                    "event=ADMIN_REFRESH_TOKEN_{}_CLEANUP_{} "
                            + "role={} adminId={}",
                    target,
                    outcome,
                    role,
                    adminId);
        }
    }

    /*
     * 로그아웃 이전에 발급된 Access Token을 사용할 수 없도록 Redis에 valid-after 커트라인을 저장한다.
     * timeout/연결 실패의 경우 Redis가 실제로 반영했을 가능성이 있으므로 후속 조회로 확인하고 필요한 경우 한 번 더 저장한다.
     */
    private void invalidateAccessTokenOrThrow(
            String role,
            Long adminId,
            LocalDateTime cutoff) {

        Duration ttl =
                Duration.ofMillis(
                        jwtTokenProvider
                                .getAccessTokenValidityMs());

        // 1. 최초 커트라인 저장
        try {
            accessTokenValidAfterRepository
                    .invalidateBefore(
                            role,
                            adminId,
                            cutoff,
                            ttl);

            return;

        } catch (
                QueryTimeoutException
                | DataAccessResourceFailureException e) {

            log.warn(
                    "event=ADMIN_ACCESS_TOKEN_INVALIDATION_UNKNOWN "
                            + "role={} adminId={}",
                    role,
                    adminId,
                    e);

        } catch (DataAccessException e) {

            throw logoutFailed(
                    role,
                    adminId,
                    e);
        }

        // 2. timeout이 발생했더라도 실제로 Redis에 반영됐는지 확인한다.
        if (isAccessTokenInvalidationConfirmed(
                role,
                adminId,
                cutoff)) {

            return;
        }

        // 3. 아직 반영 여부를 확인하지 못했다면 한 번 재시도한다.
        try {
            accessTokenValidAfterRepository
                    .invalidateBefore(
                            role,
                            adminId,
                            cutoff,
                            ttl);

            return;

        } catch (
                QueryTimeoutException
                | DataAccessResourceFailureException e) {

            log.warn(
                    "event=ADMIN_ACCESS_TOKEN_INVALIDATION_RETRY_UNKNOWN "
                            + "role={} adminId={}",
                    role,
                    adminId,
                    e);

        } catch (DataAccessException e) {

            throw logoutFailed(
                    role,
                    adminId,
                    e);
        }

        // 4. 재시도까지 결과가 미확정이면 마지막으로 확인한다.
        // 끝까지 확인할 수 없다면 204 성공으로 응답하지 않는다.
        if (!isAccessTokenInvalidationConfirmed(
                role,
                adminId,
                cutoff)) {

            throw logoutFailed(
                    role,
                    adminId,
                    null);
        }
    }

    /*
     * 저장된 커트라인이 cutoff 이상인지 간접적으로 확인한다.
     * cutoff 직전 시각의 토큰이 더 이상 유효하지 않다면 logout 시 기록한 커트라인이 반영된 것으로 볼 수 있다.
     */
    private boolean isAccessTokenInvalidationConfirmed(
            String role,
            Long adminId,
            LocalDateTime cutoff) {

        try {
            return !accessTokenValidAfterRepository
                    .isValidAfter(
                            role,
                            adminId,
                            cutoff.minusNanos(
                                    CUTOFF_CONFIRMATION_OFFSET_NANOS));

        } catch (DataAccessException e) {

            log.warn(
                    "event=ADMIN_ACCESS_TOKEN_INVALIDATION_CONFIRM_FAILED "
                            + "role={} adminId={}",
                    role,
                    adminId,
                    e);

            return false;
        }
    }

    // Access Token 차단 결과를 확정하지 못했을 때 ADMIN-010 예외를 생성한다.
    private AdminException logoutFailed(
            String role,
            Long adminId,
            DataAccessException cause) {

        log.error(
                "event=ADMIN_ACCESS_TOKEN_INVALIDATION_FAILED "
                        + "role={} adminId={}",
                role,
                adminId,
                cause);

        return cause == null
                ? new AdminException(
                AdminErrorCode.LOGOUT_FAILED)
                : new AdminException(
                AdminErrorCode.LOGOUT_FAILED,
                cause);
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

    private String maskLoginId(String loginId) { return PiiMasker.maskGeneric(loginId, 2, 1); }
}