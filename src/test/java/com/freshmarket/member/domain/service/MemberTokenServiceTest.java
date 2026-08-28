package com.freshmarket.member.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.auth.AuthCookieFactory;
import com.freshmarket.common.auth.jwt.AccessTokenValidAfterRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository.RefreshTokenData;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository.RotateOutcome;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.member.domain.MemberLogoutEvent;
import com.freshmarket.member.domain.entity.Member;
import com.freshmarket.member.domain.entity.SocialType;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.exception.AuthErrorCode;
import com.freshmarket.member.domain.exception.AuthException;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
class MemberTokenServiceTest {

    private static final String TEST_JWT_SECRET = "test-jwt-secret-key-must-be-at-least-32-bytes-long";

    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AccessTokenValidAfterRepository accessTokenValidAfterRepository;

    private AuthCookieFactory authCookieFactory;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private HttpServletResponse response;

    private MemberTokenService sut;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(TEST_JWT_SECRET, 1_800_000L, 1_209_600_000L);
        authCookieFactory = new AuthCookieFactory(jwtTokenProvider);

        sut = new MemberTokenService(jwtTokenProvider, refreshTokenRepository, accessTokenValidAfterRepository,
                authCookieFactory, memberRepository, eventPublisher, Clock.systemDefaultZone());
    }

    private static Member newMember(Long id) {
        Member member = Member.register(SocialType.KAKAO, "kakao-1", 1L);
        setId(member, id);
        return member;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setRefreshTokenExpiresAt(Member member, LocalDateTime expiresAt) {
        try {
            Field field = Member.class.getDeclaredField("refreshTokenExpiresAt");
            field.setAccessible(true);
            field.set(member, expiresAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- issue() ----

    @Test
    void 발급하면_accessToken과_refreshToken_쿠키가_둘_다_실린다() {
        Member member = newMember(1L);

        MemberTokenService.IssueResult result = sut.issue(member, true, response);

        // 실제 JwtTokenProvider가 서명한 진짜 토큰인지, 클레임이 맞는지 검증(accessToken만 —
        // refreshToken은 opaque라 더 이상 JWT가 아니다)
        assertThat(jwtTokenProvider.validateToken(result.accessToken())).isTrue();
        assertThat(jwtTokenProvider.getId(result.accessToken())).isEqualTo(1L);
        assertThat(jwtTokenProvider.getType(result.accessToken())).isEqualTo(TokenType.MEMBER);
        assertThat(result.expiresInSeconds()).isEqualTo(1800L);

        // 응답에 실제로 실린 Set-Cookie 헤더를 캡처해서 AuthCookieFactory가 만든 속성을 직접 확인
        ArgumentCaptor<String> cookieCaptor = ArgumentCaptor.forClass(String.class);
        verify(response, times(2)).addHeader(eq(HttpHeaders.SET_COOKIE), cookieCaptor.capture());
        List<String> cookies = cookieCaptor.getAllValues();

        assertThat(cookies).anySatisfy(c -> {
            assertThat(c).startsWith("refreshToken=");
            assertThat(c).contains("Path=/v1/auth/");
            assertThat(c).contains("HttpOnly");
            assertThat(c).contains("SameSite=Strict");
        });
        assertThat(cookies).anySatisfy(c -> {
            assertThat(c).startsWith("accessToken=");
            assertThat(c).contains("Path=/");
            assertThat(c).contains("HttpOnly");
        });
    }

    @Test
    void redis_저장이_실패해도_발급_자체는_끝난다() {
        Member member = newMember(1L);
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(refreshTokenRepository).save(any(), any(), any(), any(), anyBoolean(), any());

        assertThatCode(() -> sut.issue(member, false, response)).doesNotThrowAnyException();
    }

    @Test
    void db_백업_저장이_실패해도_발급_자체는_끝난다() {
        Member member = newMember(1L);
        when(memberRepository.updateRefreshToken(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> sut.issue(member, false, response)).doesNotThrowAnyException();
    }

    @Test
    void db_redis_둘_다_실패해도_발급_자체는_끝난다() {
        // 방금 발급한 refreshToken이 DB/Redis 어디에도 안 남는 조합 — error는 아니지만
        // (fail-closed라 위험한 상태로 남지 않는다) 발급 응답 자체는 예외 없이 끝나야 한다.
        Member member = newMember(1L);
        when(memberRepository.updateRefreshToken(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(refreshTokenRepository).save(any(), any(), any(), any(), anyBoolean(), any());

        assertThatCode(() -> sut.issue(member, false, response)).doesNotThrowAnyException();
    }

    @Test
    void db_redis_둘_다_실패하면_refreshToken_쿠키_없이_accessToken만_내려간다() {
        // (2026-08-26, FUN-2-02) 어디에도 안 남는 죽은 refreshToken을 클라이언트에 심어봐야
        // 다음 reissue()에서 REFRESH_TOKEN_INVALID로만 드러난다 — 아예 쿠키를 생략하고
        // accessToken만 내려준다(AT-only 폴백). 로그인 자체는 실패시키지 않는다.
        Member member = newMember(1L);
        when(memberRepository.updateRefreshToken(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(refreshTokenRepository).save(any(), any(), any(), any(), anyBoolean(), any());

        MemberTokenService.IssueResult result = sut.issue(member, false, response);

        assertThat(jwtTokenProvider.validateToken(result.accessToken())).isTrue();

        ArgumentCaptor<String> cookieCaptor = ArgumentCaptor.forClass(String.class);
        verify(response, times(1)).addHeader(eq(HttpHeaders.SET_COOKIE), cookieCaptor.capture());
        List<String> cookies = cookieCaptor.getAllValues();

        assertThat(cookies).hasSize(1);
        assertThat(cookies.get(0)).startsWith("accessToken=");
        assertThat(cookies).noneMatch(c -> c.startsWith("refreshToken="));
    }

    @Test
    void db나_redis_중_하나만_성공하면_refreshToken_쿠키도_그대로_내려간다() {
        // AT-only 폴백은 "둘 다" 실패했을 때만 탄다 — 한쪽만 실패해도 refreshToken은 최소
        // 한 곳엔 살아있으니(재발급 가능) 쿠키를 그대로 내려줘야 한다.
        // updateRefreshToken()은 stub 안 하면 Mockito가 int 기본값 0을 돌려주는데,
        // trySaveDbBackup()은 0을 "갱신된 행 없음(=실패)"으로 해석한다 — DB 쪽을 진짜
        // 성공시키려면 1을 명시적으로 stub해야 한다(안 그러면 둘 다 실패로 처리돼 이 테스트가
        // 검증하려는 "한쪽만 실패" 상황 자체가 만들어지지 않는다).
        Member member = newMember(1L);
        when(memberRepository.updateRefreshToken(any(), any(), any())).thenReturn(1);
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(refreshTokenRepository).save(any(), any(), any(), any(), anyBoolean(), any());

        sut.issue(member, false, response);

        ArgumentCaptor<String> cookieCaptor = ArgumentCaptor.forClass(String.class);
        verify(response, times(2)).addHeader(eq(HttpHeaders.SET_COOKIE), cookieCaptor.capture());
        assertThat(cookieCaptor.getAllValues()).anyMatch(c -> c.startsWith("refreshToken="));
    }

    // ---- reissue() ----
    // (2026-08-19) opaque 전환 이후 reissue(String)만 받는다 — 컨트롤러가 미리 클레임을 안 읽고
    // 그대로 넘기므로, 여기서 refreshTokenRepository.compareAndRotate()의 결과(RotateOutcome)로만
    // 소유자를 안다. NOT_FOUND(정말 모르는 토큰)와 REUSE_DETECTED(한 번 회전되고 죽은 토큰의
    // 재사용, tombstone 덕에 소유자는 앎)를 구분한다.

    @Test
    void 전혀_모르는_토큰이면_재사용_의심_없이_예외() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.notFound());

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
        verify(memberRepository, never()).findById(any());
        // 소유자를 아예 모르니 강제 종료할 세션도 없다 — revoke 관련 저장소 호출이 없어야 한다
        verify(refreshTokenRepository, never()).findActiveHash(any(), any());
    }

    @Test
    void 이미_회전되고_죽은_토큰이_재사용되면_그_회원의_세션을_강제종료하고_예외() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.reuseDetected(new RefreshTokenData(1L, "ROLE_USER", TokenType.MEMBER, false)));
        when(refreshTokenRepository.findActiveHash("ROLE_USER", 1L)).thenReturn(Optional.of("current-hash"));

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);

        // revoke(1L, "ROLE_USER", false)가 실제로 호출돼 현재 세션(activeKey가 가리키는 해시)을 지웠는지
        verify(memberRepository).clearRefreshToken(1L);
        verify(refreshTokenRepository).revokeIfActiveHashMatches("current-hash", "ROLE_USER", 1L);
        verify(accessTokenValidAfterRepository).invalidateBefore(eq("ROLE_USER"), eq(1L), any(), any());
    }

    @Test
    void 존재하지_않는_회원의_리프레시면_예외() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.success(new RefreshTokenData(1L, "ROLE_USER", TokenType.MEMBER, false)));
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void 탈퇴한_회원의_리프레시면_토큰을_비우고_예외() {
        Member withdrawn = newMember(1L);
        withdrawn.withdraw();
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.success(new RefreshTokenData(1L, "ROLE_USER", TokenType.MEMBER, false)));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(withdrawn));
        when(refreshTokenRepository.findActiveHash("ROLE_USER", 1L)).thenReturn(Optional.of("current-hash"));

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
        verify(memberRepository).clearRefreshToken(1L);
        verify(refreshTokenRepository).revokeIfActiveHashMatches("current-hash", "ROLE_USER", 1L);
    }

    @Test
    void 회전에_성공하면_새_토큰을_돌려준다() {
        Member member = newMember(1L);
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.success(new RefreshTokenData(1L, "ROLE_USER", TokenType.MEMBER, true)));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        MemberTokenService.ReissueResult result = sut.reissue("old-rt");

        assertThat(jwtTokenProvider.validateToken(result.accessToken())).isTrue();
        assertThat(jwtTokenProvider.getId(result.accessToken())).isEqualTo(1L);
        assertThat(result.expiresInSeconds()).isEqualTo(1800L);
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo("old-rt");
        assertThat(result.remember()).isTrue();
    }

    @Test
    void redis_CAS가_장애나면_DB_백업으로_폴백해서_재발급을_계속한다() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        Member member = newMember(1L);
        setRefreshTokenExpiresAt(member, LocalDateTime.now().plusDays(1));
        when(memberRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(member));
        when(memberRepository.compareAndSetRefreshToken(eq(1L), anyString(), anyString(), any())).thenReturn(1);

        MemberTokenService.ReissueResult result = sut.reissue("old-rt");

        assertThat(jwtTokenProvider.validateToken(result.accessToken())).isTrue();
        assertThat(jwtTokenProvider.getId(result.accessToken())).isEqualTo(1L);
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo("old-rt");
        // DB 폴백 경로에서는 remember를 DB가 모르니 안전한 쪽(false)으로 저하시킨다
        assertThat(result.remember()).isFalse();
    }

    @Test
    void redis_CAS가_장애나고_DB에도_해당_토큰이_없으면_예외() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        when(memberRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void redis_CAS가_장애나고_DB_CAS도_경합에서_지면_예외() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        Member member = newMember(1L);
        setRefreshTokenExpiresAt(member, LocalDateTime.now().plusDays(1));
        when(memberRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(member));
        when(memberRepository.compareAndSetRefreshToken(eq(1L), anyString(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }

    // ---- revoke() ----

    @Test
    void 로그아웃하면_저장소_세_곳을_모두_정리한다() {
        when(refreshTokenRepository.findActiveHash("ROLE_USER", 1L)).thenReturn(Optional.of("current-hash"));

        sut.revoke(1L, "ROLE_USER", false);

        verify(memberRepository).clearRefreshToken(1L);
        verify(refreshTokenRepository).revokeIfActiveHashMatches("current-hash", "ROLE_USER", 1L);
        verify(accessTokenValidAfterRepository).invalidateBefore(eq("ROLE_USER"), eq(1L), any(), any());
    }

    @Test
    void activeKey가_유실되면_db_백업_해시로_대신_지운다() {
        Member member = newMember(1L);
        try {
            Field field = Member.class.getDeclaredField("refreshTokenHash");
            field.setAccessible(true);
            field.set(member, "db-backed-up-hash");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        when(refreshTokenRepository.findActiveHash("ROLE_USER", 1L)).thenReturn(Optional.empty());
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        sut.revoke(1L, "ROLE_USER", false);

        verify(refreshTokenRepository)
                .revokeIfActiveHashMatches("db-backed-up-hash", "ROLE_USER", 1L);
    }

    @Test
    void db_삭제가_실패해도_나머지_정리는_계속된다() {
        when(refreshTokenRepository.findActiveHash("ROLE_USER", 1L)).thenReturn(Optional.of("current-hash"));
        doThrow(new DataAccessResourceFailureException("db down")).when(memberRepository).clearRefreshToken(1L);

        sut.revoke(1L, "ROLE_USER", false);

        verify(refreshTokenRepository).revokeIfActiveHashMatches("current-hash", "ROLE_USER", 1L);
    }

    @Test
    void redis_조회가_실패해도_나머지_정리는_계속된다() {
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(refreshTokenRepository).findActiveHash("ROLE_USER", 1L);

        sut.revoke(1L, "ROLE_USER", false);

        verify(memberRepository).clearRefreshToken(1L);
        verify(accessTokenValidAfterRepository).invalidateBefore(eq("ROLE_USER"), eq(1L), any(), any());
        verify(refreshTokenRepository).deleteActiveKey("ROLE_USER", 1L);
    }

    @Test
    void invalidateBefore가_실패해도_로그아웃_자체는_예외_없이_끝난다() {
        // (2026-08-20, REL-2-11) 이것도 다른 세 단계처럼 실패를 삼키게 바꿨다 — 안 그러면
        // 여기서 던진 예외가 컨트롤러까지 올라가 이미 성공한 정리 작업들이 있는데도 로그아웃
        // 응답이 500이 된다.
        when(refreshTokenRepository.findActiveHash("ROLE_USER", 1L)).thenReturn(Optional.of("current-hash"));
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(accessTokenValidAfterRepository).invalidateBefore(eq("ROLE_USER"), eq(1L), any(), any());

        assertThatCode(() -> sut.revoke(1L, "ROLE_USER", false)).doesNotThrowAnyException();
        verify(memberRepository).clearRefreshToken(1L);
        verify(refreshTokenRepository).revokeIfActiveHashMatches("current-hash", "ROLE_USER", 1L);
    }

    @Test
    void 외부세션_로그아웃_플래그가_true면_카카오_로그아웃_이벤트를_발행한다() {
        when(refreshTokenRepository.findActiveHash("ROLE_USER", 1L)).thenReturn(Optional.of("current-hash"));
        Member member = newMember(1L);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        sut.revoke(1L, "ROLE_USER", true);

        // 카카오 호출 자체는 @Transactional 밖(KakaoLogoutEventListener, AFTER_COMMIT)에서 일어난다 —
        // 여기서는 이벤트가 올바른 값으로 발행됐는지만 확인한다.
        verify(eventPublisher).publishEvent(new MemberLogoutEvent(1L, "kakao-1"));
    }

    @Test
    void 외부세션_로그아웃_플래그가_false면_카카오_로그아웃_이벤트를_발행하지_않는다() {
        when(refreshTokenRepository.findActiveHash("ROLE_USER", 1L)).thenReturn(Optional.of("current-hash"));

        sut.revoke(1L, "ROLE_USER", false);

        verify(eventPublisher, never()).publishEvent(any());
        verify(memberRepository, never()).findById(anyLong());
    }
}
