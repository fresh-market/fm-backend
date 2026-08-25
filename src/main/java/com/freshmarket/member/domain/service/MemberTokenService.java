package com.freshmarket.member.domain.service;

import com.freshmarket.common.auth.AuthCookieFactory;
import com.freshmarket.common.auth.jwt.AccessTokenValidAfterRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.auth.opaque.OpaqueTokenGenerator;
import com.freshmarket.common.auth.opaque.TokenHasher;
import com.freshmarket.member.domain.MemberLogoutEvent;
import com.freshmarket.member.domain.entity.Member;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.exception.AuthErrorCode;
import com.freshmarket.member.domain.exception.AuthException;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// issue()/reissue() 둘 다 accessToken(+수명)을 반환값에 담아 컨트롤러가 응답 본문/쿠키를
// 조립하는 데 쓸 수 있게 한다. 재발급 실패는 BadCredentialsException(스프링 시큐리티 제네릭
// 타입) 대신 AuthException(AUTH-004)으로 던져 문서가 정한 에러코드가 그대로 응답에 실린다.
/**
 * 회원 로그인/재발급/로그아웃 시 토큰(access/refresh) 발급·회전·폐기를 담당. common.auth.opaque의
 * RefreshTokenRepository(순수 Redis)를 1차 저장소로 쓰고, Member 행의
 * refreshTokenHash/refreshTokenExpiresAt에 DB 백업을 write-through로 남긴다.
 *
 * (2026-08-19) opaque 토큰 전환(SEC-1-04): 리프레시 토큰은 더 이상 JWT가 아니라
 * OpaqueTokenGenerator가 만든 무작위 문자열이다 — 클라이언트가 보낸 토큰만 봐서는 누구 건지
 * 전혀 알 수 없어서, reissue()가 "토큰에서 클레임을 먼저 읽고 조회"가 아니라 "Redis 조회부터
 * 하고 나서 알아내는" 순서로 뒤집혔다.
 *
 * (2026-08-19 추가) 위 문제를 두 갈래로 보강했다:
 * 1. 재사용 탐지(REUSE_DETECTED) — RefreshTokenRepository가 회전된 옛 토큰을 곧바로 지우지 않고
 *    tombstone으로 짧게 남겨두므로(refresh_token_rotate.lua 참고), 죽은 토큰이 재생되면 그
 *    소유자를 알아내 revoke()로 현재 세션을 강제 종료한다(도난 대응 복원).
 * 2. Redis 완전 장애 — compareAndRotate()가 DataAccessException을 던지면
 *    reissueViaDbFallback()으로 넘어가 Member.refreshTokenHash(DB 백업)로 회원을 역조회하고
 *    DB 레벨 CAS(MemberRepository.compareAndSetRefreshToken)로 회전을 계속한다. 이 경로에서는
 *    remember 플래그를 DB에 저장해두지 않으므로 안전하게 false로 취급한다(세션 쿠키가 된다).
 *
 * (2026-08-19 추가) DB/Redis에 남는 만료 시각 계산은 System/LocalDateTime.now() 대신 주입받은
 * Clock을 쓴다 — admin-login 브랜치의 AdminAuthService와 같은 패턴이다(Clock을 서비스 레이어에서
 * "영속되는 시각" 계산에만 쓰고, JwtTokenProvider 자체는 Clock을 안 받는다). 두 브랜치를 합칠 때
 * 충돌을 줄이려고 이 범위에 맞췄다 — JwtTokenProvider.java의 관련 주석 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final AuthCookieFactory authCookieFactory;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public record IssueResult(String accessToken, long expiresInSeconds) {
    }

    public record ReissueResult(String accessToken, long expiresInSeconds, String refreshToken, boolean remember) {
    }

    /** 카카오 로그인 성공 시 토큰 발급. accessToken/refreshToken 둘 다 쿠키로 나가고, accessToken은
     * 호출부(컨트롤러)가 만료 시각 등 안내용으로 쓸 수 있게 반환값에도 담는다. */
    @Transactional
    public IssueResult issue(Member member, boolean rememberMe, HttpServletResponse response) {
        Long memberId = member.getId();
        String role = member.getRole().name();
        Duration ttl = Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs());

        String accessToken = jwtTokenProvider.createAccessToken(memberId, TokenType.MEMBER, role);
        String refreshToken = OpaqueTokenGenerator.generate();

        boolean dbBackupSaved = trySaveDbBackup(memberId, TokenHasher.sha256(refreshToken), LocalDateTime.now(clock).plus(ttl));
        boolean redisSaved = true;
        try {
            refreshTokenRepository.save(refreshToken, memberId, role, TokenType.MEMBER, rememberMe, ttl);
        } catch (DataAccessException e) {
            redisSaved = false;
            log.warn("event=REDIS_SAVE_FAILED role={} id={}", role, memberId, e);
        }
        // (2026-08-25) DB 백업 저장과 Redis 저장은 서로의 결과를 모른 채 각자 로그를 남긴다 —
        // 둘 다 실패했을 때만 여기서 따로 알린다. 위 두 로그는 "반대쪽은 됐다"고 주장하지
        // 않으니 각자는 정확하지만, "둘 다 안 됐다"는 조합 자체는 둘 중 하나만 봐서는 안 드러난다.
        // error는 아니다 — 방금 쿠키로 내려준 refreshToken이 DB/Redis 어디에도 안 남아 재발급만
        // 안 될 뿐(fail-closed), 권한이 위험하게 남는 상태가 아니라 사용자는 accessToken 만료
        // 시점에 재로그인하면 정상화된다. 다만 두 저장소가 동시에 실패하는 빈도는 인프라 신호일
        // 수 있어 이름은 따로 둔다.
        if (!dbBackupSaved && !redisSaved) {
            log.warn("event=REFRESH_TOKEN_ISSUE_PERSIST_BOTH_FAILED memberId={} role={} — 방금 발급한 "
                    + "refreshToken이 DB/Redis 어디에도 없음(재발급 불가, 다음 재로그인 때 정상화됨)", memberId, role);
        }

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.refreshTokenCookie(refreshToken, rememberMe).toString());
        // (2026-08-18 16:20) accessToken도 다시 쿠키로 내려준다(요청에 따라 헤더 방식에서 되돌림).
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(accessToken).toString());

        return new IssueResult(accessToken, jwtTokenProvider.getAccessTokenValidityMs() / 1000);
    }

    /**
     * POST /v1/auth/tokens:refresh용. opaque 토큰이라 컨트롤러가 미리 검증/디코딩할 게 없다 —
     * 쿠키에서 꺼낸 문자열을 그대로 넘겨받아 여기서 Redis 조회부터 시작한다.
     */
    @Transactional
    public ReissueResult reissue(String oldRefreshToken) {
        String newRefreshToken = OpaqueTokenGenerator.generate();
        Duration ttl = Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs());
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(ttl);

        RefreshTokenRepository.RotateOutcome outcome;
        try {
            outcome = refreshTokenRepository.compareAndRotate(oldRefreshToken, newRefreshToken, ttl);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_CAS_FAILED — Redis 장애, DB 백업으로 재발급 폴백 시도", e);
            return reissueViaDbFallback(oldRefreshToken, newRefreshToken, expiresAt);
        }

        if (outcome.isReuseDetected()) {
            // (2026-08-19) 이미 tombstone된(=한 번 회전되고 죽은) 토큰이 다시 들어옴 — 도난된 토큰이
            // 재생됐을 가능성이 높다. tombstone 덕에 소유자를 알아냈으니, 그 회원의 현재 세션(도둑이
            // 들고 있을 수도 있는 최신 토큰)까지 강제로 끊는다. 이 요청 자체는 그대로 거부한다 —
            // 죽은 토큰을 들고 있다는 사실 자체는 신원 증명이 아니라서 새 토큰을 내주면 안 된다.
            RefreshTokenRepository.RefreshTokenData reused = outcome.data();
            log.warn("event=REFRESH_TOKEN_REUSE_SUSPECTED memberId={} role={} — 세션을 강제 종료한다",
                    reused.memberId(), reused.role());
            revoke(reused.memberId(), reused.role(), false);
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (!outcome.isSuccess()) {
            log.warn("event=REFRESH_TOKEN_NOT_FOUND tokenHash={}", TokenHasher.sha256(oldRefreshToken));
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        RefreshTokenRepository.RefreshTokenData rotated = outcome.data();
        Long memberId = rotated.memberId();
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));

        if (member.isWithdrawn()) {
            revoke(memberId, rotated.role(), false);
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        String role = member.getRole().name();
        String newAccessToken = jwtTokenProvider.createAccessToken(memberId, TokenType.MEMBER, role);

        // 여기까지 왔다는 건 Redis 회전(compareAndRotate)이 이미 성공했다는 뜻이라, DB 백업만
        // best-effort다 — 실패해도 trySaveDbBackup()이 자기 상황만 로그하면 충분하고 issue()처럼
        // "Redis도 같이 실패했는지"를 따로 볼 필요는 없다.
        trySaveDbBackup(memberId, TokenHasher.sha256(newRefreshToken), expiresAt);
        return new ReissueResult(newAccessToken, jwtTokenProvider.getAccessTokenValidityMs() / 1000, newRefreshToken, rotated.remember());
    }

    /**
     * (2026-08-19) Redis가 완전히 죽었을 때만 타는 경로. opaque 토큰은 문자열 자체로는 누구 건지
     * 알 수 없어서, DB 백업(Member.refreshTokenHash)으로 역조회하는 것 말고는 신원을 확인할 방법이
     * 없다 — 그래서 findByRefreshTokenHash()가 유일한 진입점이다. 동시 요청 경합은 DB 레벨 조건부
     * UPDATE(compareAndSetRefreshToken, rows-affected 확인)로 막는다 — Redis의 Lua CAS와 같은
     * 역할을 DB 트랜잭션/WHERE 절이 대신한다.
     *
     * remember는 이 폴백 경로에서 DB에 남아있지 않다(Redis 기본 레코드에만 저장하던 값) — false로
     * 처리해 refreshToken 쿠키가 세션 쿠키가 되게 한다(더 안전한 쪽으로 저하시킨다).
     */
    private ReissueResult reissueViaDbFallback(String oldRefreshToken, String newRefreshToken, LocalDateTime expiresAt) {
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        Member member = memberRepository.findByRefreshTokenHash(oldHash)
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));

        if (member.isWithdrawn()) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (member.getRefreshTokenExpiresAt() == null || member.getRefreshTokenExpiresAt().isBefore(LocalDateTime.now(clock))) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        String newHash = TokenHasher.sha256(newRefreshToken);
        int updated = memberRepository.compareAndSetRefreshToken(member.getId(), oldHash, newHash, expiresAt);
        if (updated == 0) {
            // 동시에 다른 요청이 먼저 회전시켰다(또는 이미 다른 값으로 바뀌었다) — 재사용 의심과
            // 같은 결로 취급해 거부한다. Redis가 죽은 상태라 여기서 세션을 강제 종료할 방법까진
            // 없다(revoke()도 결국 Redis를 건드리니까) — 재로그인을 요구하는 것으로 그친다.
            log.warn("event=DB_FALLBACK_CAS_LOST memberId={}", member.getId());
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        String role = member.getRole().name();
        String newAccessToken = jwtTokenProvider.createAccessToken(member.getId(), TokenType.MEMBER, role);

        try {
            refreshTokenRepository.save(newRefreshToken, member.getId(), role, TokenType.MEMBER, false,
                    Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs()));
        } catch (DataAccessException e) {
            // DB CAS는 위에서 이미 확정적으로 성공했으므로("DB만 반영됨"이 실제로 참이다) — issue()와
            // 달리 여기선 두 저장소 결과를 따로 모아 판단할 필요가 없다.
            log.warn("event=REDIS_SAVE_FAILED_DURING_DB_FALLBACK memberId={} — DB만 반영됨", member.getId(), e);
        }

        return new ReissueResult(newAccessToken, jwtTokenProvider.getAccessTokenValidityMs() / 1000, newRefreshToken, false);
    }

    /**
     * 로그아웃/탈퇴 시 토큰 폐기. logoutExternalSession=true면 카카오 세션도 끊는다(일반
     * /members/logout에서만 true — 탈퇴 흐름은 카카오 unlink를 MemberWithdrawalEvent로 별도
     * 처리하므로 여기서는 false로 호출한다). 카카오 로그아웃 자체는 MemberLogoutEvent로 커밋
     * 이후에 호출한다(KakaoLogoutEventListener) — MemberWithdrawalEvent/KakaoUnlinkEventListener와
     * 같은 이유(DI-4-02): @Transactional 안에서 동기로 부르면 카카오 응답 대기 동안 DB 커넥션이
     * 묶인다.
     *
     * (2026-08-19 추가) 보조 인덱스(findActiveHash)가 Redis 축출/재시작으로 유실됐으면 DB 백업
     * (Member.refreshTokenHash)에서 해시를 구해 대신 지운다 — 그래야 그 해시가 자기 TTL로 자연
     * 만료될 때까지 Redis에 남아 로그아웃 후에도 재발급에 쓰이는 걸 막는다. 이 폴백을 쓰려면
     * clearRefreshToken()으로 그 컬럼을 비우기 **전에** 먼저 읽어야 한다 — 순서를 바꾸면 폴백이
     * 항상 빈 값이 된다. findById()는 이 폴백이 실제로 필요할 때(activeKey 미스)와
     * logoutExternalSession=true일 때만 부른다 — 평소(Redis 정상, 내부 로그아웃)엔 여기서
     * DB 조회가 추가로 생기지 않는다.
     */
    @Transactional
    public void revoke(Long memberId, String role, boolean logoutExternalSession) {
        Optional<String> hash;
        try {
            hash = refreshTokenRepository.findActiveHash(role, memberId);
            if (hash.isEmpty()) {
                hash = memberRepository.findById(memberId).map(Member::getRefreshTokenHash);
                hash.ifPresent(h -> log.warn("event=ACTIVE_KEY_MISSING_DB_FALLBACK_USED role={} id={}", role, memberId));
            }
        } catch (DataAccessException e) {
            log.warn("event=REDIS_LOOKUP_FAILED role={} id={} — 지울 해시를 못 구함", role, memberId, e);
            hash = Optional.empty();
        }

        try {
            memberRepository.clearRefreshToken(memberId);
        } catch (DataAccessException e) {
            log.warn("event=DB_BACKUP_DELETE_FAILED memberId={} — DB 백업 삭제 실패(계속 진행)", memberId, e);
        }

        try {
            hash.ifPresent(refreshTokenRepository::deleteByHash);
            refreshTokenRepository.deleteActiveKey(role, memberId);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_DELETE_FAILED role={} id={}", role, memberId, e);
        }

        try {
            accessTokenValidAfterRepository.invalidateBefore(role, memberId, LocalDateTime.now(clock),
                    Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
        } catch (DataAccessException e) {
            // (2026-08-20 추가, REL-2-11) 앞의 세 단계와 통일 — 이거 하나 실패했다고 로그아웃
            // 응답 자체를 500으로 만들 이유가 없다. 대가는: 이 컷라인 기록이 안 남는 동안엔
            // 이미 로그아웃된 회원의 (아직 자연 만료 전) 액세스 토큰이 계속 통할 수 있다는
            // 것인데, 이건 JwtAuthenticationFilter.isValidAfterCutoff()가 Redis 장애 시 이미
            // fail-open으로 감수하기로 한 것과 같은 종류의 리스크라 새로 늘어나는 게 아니다.
            log.warn("event=INVALIDATE_BEFORE_FAILED role={} id={}", role, memberId, e);
        }

        if (logoutExternalSession) {
            memberRepository.findById(memberId)
                    .map(Member::getProviderUserId)
                    .ifPresent(providerUserId -> eventPublisher.publishEvent(new MemberLogoutEvent(memberId, providerUserId)));
        }
    }

    /**
     * (2026-08-25) 반환값을 true/false로 알려준다 — 이 결과와 Redis 저장 결과를 호출부가 같이
     * 모아서 "둘 다 실패"를 따로 판단할 수 있어야 하므로(issue() 참고), 예전처럼 자기 상황만 보고
     * "반대쪽은 됐다"고 단정하는 문구를 로그에 넣지 않는다 — 그 가정이 항상 맞는 건 아니다.
     */
    private boolean trySaveDbBackup(Long memberId, String tokenHash, LocalDateTime expiresAt) {
        try {
            int updated = memberRepository.updateRefreshToken(memberId, tokenHash, expiresAt);
            if (updated == 0) {
                log.warn("event=DB_BACKUP_SAVE_SKIPPED memberId={} — 대상 행을 찾지 못함", memberId);
                return false;
            }
            return true;
        } catch (DataAccessException e) {
            log.warn("event=DB_BACKUP_SAVE_FAILED memberId={}", memberId, e);
            return false;
        }
    }
}
