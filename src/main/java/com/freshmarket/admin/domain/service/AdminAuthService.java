package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.dto.AdminLoginRequest;
import com.freshmarket.admin.domain.dto.AdminLoginResponse;
import com.freshmarket.admin.domain.dto.AdminLoginResult;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.opaque.OpaqueTokenGenerator;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.jwt.TokenType;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import com.freshmarket.common.auth.opaque.TokenHasher;
import com.freshmarket.common.logging.PiiMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자 로그인만 다룬다. 로그아웃, 토큰 재발급, 비밀번호 변경은 별도 PR 이다 (auth.md 참고).
 *
 * (merge: feat/member-auth와 합치며 추가) JWT 서명·액세스 토큰 발급은 member/admin이 공유하는
 * common.auth.jwt.JwtTokenProvider를 그대로 쓴다 — admin이 따로 두던 common.security.JwtTokenProvider와
 * 거의 동일한 구현을 독립적으로 만들었던 것이라, 중복을 없애고 이쪽으로 통합했다.
 * 액세스 토큰 유효기간도 이제 JwtTokenProvider가 갖고 있어(jwt.access-token-validity-ms) 별도 파라미터가 필요 없다.
 * 리프레시 토큰은 member와 같은 공통 RefreshTokenRepository(Redis)에 저장한다.
 * 로그인은 최초 발급만 담당하고, Rotation은 별도 재발급 API에서 compareAndRotate()로 처리한다.
 *
 * Redis 완전 장애 시에도 로그인이 막히지 않도록, Refresh Token은 DB에 먼저 write-through로 백업한 뒤 Redis 저장을 시도한다.
 * DB 백업은 @Modifying UPDATE(AdminRepository.updateRefreshToken())라서 활성 트랜잭션이 없으면
 * InvalidDataAccessApiUsageException("Executing an update/delete query")이 난다 — 그래서 login() 전체를 @Transactional로 감싼다.
 * MemberTokenService.issue()도 같은 이유로 Redis 호출을 포함해 메서드 전체가
 * @Transactional이다(트랜잭션 안에서 외부 I/O를 하는 대가보다, DB 백업 자체가 항상 저장되는 게 우선이라는 판단).
 */
@Slf4j
@Service
public class AdminAuthService {

    // 실제 계정과 무관한 값이다. 계정이 없을 때도 이 해시로 BCrypt 를 돌려 응답 시간을 맞춘다 (SEC-6-04)
    private static final String DUMMY_PASSWORD_SOURCE = "dummy-password-for-constant-time-comparison";

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshTokenValiditySeconds;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminAuthService(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            Clock clock,
            @Value("${ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS:86400}") long refreshTokenValiditySeconds) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
        this.refreshTokenValiditySeconds = refreshTokenValiditySeconds;
        // 같은 인코더로 미리 만들어 둬야 진짜 비밀번호 검증과 연산 비용(코스트 팩터)이 완전히 같다
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD_SOURCE);
    }

    @Transactional
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

        String accessToken = jwtTokenProvider.createAccessToken(
                admin.getId(), TokenType.ADMIN, admin.getRole().toAuthority());

        String rawRefreshToken = OpaqueTokenGenerator.generate();
        issueRefreshToken(admin, rawRefreshToken, Duration.ofSeconds(refreshTokenValiditySeconds));

        AdminLoginResponse response = new AdminLoginResponse(
                jwtTokenProvider.getAccessTokenValidityMs() / 1000,
                new AdminLoginResponse.AdminSummary(
                        admin.getLoginId(), admin.getName(), admin.getRole()));

        log.info("event=ADMIN_LOGIN success=true adminId={} loginId={}",
                admin.getId(), maskLoginId(admin.getLoginId()));

        // 두 토큰 원문은 응답 본문이 아니라 컨트롤러가 만드는 HttpOnly 쿠키로만 나간다
        return new AdminLoginResult(response, accessToken, rawRefreshToken, refreshTokenValiditySeconds);
    }

    private String maskLoginId(String loginId) { return PiiMasker.maskGeneric(loginId, 2, 1); }

    /*
     * Refresh Token을 DB에 먼저 write-through로 백업한 뒤 Redis 저장을 시도한다.
     * 순서가 이런 이유: Redis가 죽어 있어도 DB 백업만은 남겨야 재발급/로그아웃이 나중에
     * 이 값을 근거로 계속 동작할 수 있다. 반대로 Redis부터 쓰면, DB 쓰기가 실패했을 때
     * "Redis에는 있는데 DB 백업은 없는" 상태가 남아 오히려 백업의 의미가 없어진다.
     * 두 저장 모두 실패해도 로그인 응답 자체는 막지 않는다 — 이미 발급된 accessToken/rawRefreshToken은
     * 그대로 클라이언트에 내려가고, 저장 실패는 로그로만 남긴다(MemberTokenService.issue() 참고).
     */
    private void issueRefreshToken(Admin admin, String rawRefreshToken, Duration ttl) {
        trySaveDbBackup(admin.getId(), TokenHasher.sha256(rawRefreshToken), LocalDateTime.now(clock).plus(ttl));

        try {
            refreshTokenRepository.save(
                    rawRefreshToken,
                    admin.getId(),
                    admin.getRole().toAuthority(),
                    TokenType.ADMIN,
                    false,
                    ttl);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_LOGIN_REDIS_SAVE_FAILED adminId={} — DB 백업만 반영됨", admin.getId(), e);
        }
    }

    private void trySaveDbBackup(Long adminId, String tokenHash, LocalDateTime expiresAt) {
        try {
            int updated = adminRepository.updateRefreshToken(adminId, tokenHash, expiresAt);
            if (updated == 0) {
                log.warn("event=ADMIN_LOGIN_DB_BACKUP_SAVE_SKIPPED adminId={} — 대상 행을 찾지 못함", adminId);
            }
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_LOGIN_DB_BACKUP_SAVE_FAILED adminId={} — Redis만 반영됨(DB 백업 유실 가능)", adminId, e);
        }
    }
}
