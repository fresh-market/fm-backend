package com.freshmarket.common.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Access 토큰 생성·파싱·검증 담당. member/admin 공용 인증 인프라라 common.auth 소속.
 * role 클레임은 Spring Security 권한 문자열 그대로("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN")를
 * 담는다 — MemberRole.name(), AdminRole.toAuthority() 양쪽 다 이 포맷으로 맞춰뒀다.
 *
 * (2026-08-19) opaque 토큰 전환 이후 리프레시 토큰은 이 클래스가 더 이상 만들지 않는다
 * (OpaqueTokenGenerator 참고, SEC-1-04 정리). refreshTokenValidityMs는 리프레시 토큰의 TTL
 * 정책값으로 계속 여기 남겨둔다 — JWT를 만들진 않지만 "액세스/리프레시 토큰 수명 정책을 한
 * 곳에서 들고 있는다"는 원래 역할은 그대로 유효하다.
 *
 * (2026-08-19 추가, 되돌림) 한때 이 클래스에도 Clock을 주입해서 발급(issuedAt/expiration)뿐
 * 아니라 jjwt 파서의 만료 판정(io.jsonwebtoken.Clock 어댑터)까지 결정적으로 만든 적이 있다 —
 * 그러면 "만료 1초 전엔 유효, 1초 후엔 무효" 같은 JWT 자체의 경계를 Clock.fixed(...)만으로 테스트가
 * 재현할 수 있다(실제로 그렇게 짠 테스트가 한때 있었다). 근데 admin-login 브랜치의 동급 클래스
 * (common.security.JwtTokenProvider)는 Clock을 안 받고 그냥 Instant.now()를 쓴다 — 그쪽은 Clock을
 * 그 상위 서비스(AdminAuthService)에서 "DB에 영속되는 리프레시 토큰 만료 시각" 계산에만 쓰고,
 * JWT 자체의 iat/exp에는 안 쓴다. 두 브랜치 합칠 때 충돌을 줄이려고 이 클래스는 admin-login
 * 패턴(얕게 — 서비스 레이어에만 Clock)에 맞춰 되돌렸다. MemberTokenService의 LocalDateTime.now()
 * 호출들에 Clock이 들어가 있는 게 그 자리다. 더 철저한 결정성이 필요해지면(위에서 설명한 JWT
 * 경계 테스트) 여기 다시 Clock을 주입하고 Jwts.parser()에 .clock(() -> Date.from(Instant.now(clock)))
 * 를 추가하면 된다 — 다만 그럴 거면 admin-login 쪽도 같이 맞추자고 팀에 먼저 얘기하는 게 좋다.
 */
@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long accessTokenValidityMs;
    private final long refreshTokenValidityMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity-ms}") long accessTokenValidityMs,
            @Value("${jwt.refresh-token-validity-ms}") long refreshTokenValidityMs
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenValidityMs = accessTokenValidityMs;
        this.refreshTokenValidityMs = refreshTokenValidityMs;
    }

    public String createAccessToken(Long id, TokenType type, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(id))
                .claim("type", type.name())
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenValidityMs)))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public TokenType getType(String token) {
        String type = parseClaims(token).get("type", String.class);
        if (type == null) {
            return null;
        }
        // (CMP-3-03) 위조되거나 예전 포맷의 토큰이 "type" 클레임에 MEMBER/ADMIN이 아닌 값을 담고
        // 있으면 TokenType.valueOf가 IllegalArgumentException을 던진다. 이 메서드의 유일한 호출부인
        // JwtAuthenticationFilter는 이미 반환값이 null이면 인증을 건너뛰는 패턴(type == null 체크)을
        // 쓰고 있으므로, 여기서도 예외를 삼키고 null을 반환해 같은 경로로 흡수시킨다 — 그러지 않으면
        // 필터 체인 밖으로 예외가 새어나가 GlobalExceptionHandler를 거치지 못한 채 비정형 500이 된다.
        try {
            return TokenType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String getRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public LocalDateTime getIssuedAt(String token) {
        Date issuedAt = parseClaims(token).getIssuedAt();
        return issuedAt == null ? null : LocalDateTime.ofInstant(issuedAt.toInstant(), ZoneId.systemDefault());
    }

    public long getAccessTokenValidityMs() {
        return accessTokenValidityMs;
    }

    public long getRefreshTokenValidityMs() {
        return refreshTokenValidityMs;
    }
}
