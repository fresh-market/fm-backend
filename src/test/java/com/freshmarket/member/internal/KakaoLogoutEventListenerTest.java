package com.freshmarket.member.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.freshmarket.member.internal.client.KakaoLogoutClient;
import com.freshmarket.member.internal.exception.MemberErrorCode;
import com.freshmarket.member.internal.exception.MemberException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * (2026-08-27, PR 리뷰 P1) "logout이 실패해도 원래 로그아웃 요청이 성공하는지" 검증 — 이 리스너는
 * AFTER_COMMIT이라 원 로그아웃 트랜잭션은 이미 커밋된 뒤지만, kakaoLogoutClient.logout()이 던지는
 * 예외가 밖으로 새면(비동기 스레드라 원 요청에 영향은 없어도) 미처리 예외로 로그가 시끄러워지고
 * 의도(로그만 남기고 넘어감, KakaoUnlinkEventListener와 달리 아웃박스도 없음)와 어긋난다.
 */
@ExtendWith(MockitoExtension.class)
class KakaoLogoutEventListenerTest {

    @Mock
    private KakaoLogoutClient kakaoLogoutClient;

    private KakaoLogoutEventListener sut;

    @BeforeEach
    void setUp() {
        sut = new KakaoLogoutEventListener(kakaoLogoutClient);
    }

    @Test
    void 카카오_로그아웃이_성공하면_그대로_끝난다() {
        MemberLogoutEvent event = new MemberLogoutEvent(1L, "kakao-1");

        assertThatCode(() -> sut.handle(event)).doesNotThrowAnyException();

        verify(kakaoLogoutClient).logout("kakao-1");
    }

    @Test
    void 카카오_로그아웃이_MemberException으로_실패해도_밖으로_전파하지_않는다() {
        MemberLogoutEvent event = new MemberLogoutEvent(1L, "kakao-1");
        doThrow(new MemberException(MemberErrorCode.KAKAO_LOGOUT_FAILED))
                .when(kakaoLogoutClient).logout("kakao-1");

        assertThatCode(() -> sut.handle(event)).doesNotThrowAnyException();
    }

    @Test
    void 서킷이_OPEN이라_호출_자체가_막혀도_밖으로_전파하지_않는다() {
        MemberLogoutEvent event = new MemberLogoutEvent(1L, "kakao-1");
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("kakaoLogout-test");
        circuitBreaker.transitionToOpenState();
        doThrow(CallNotPermittedException.createCallNotPermittedException(circuitBreaker))
                .when(kakaoLogoutClient).logout("kakao-1");

        assertThatCode(() -> sut.handle(event)).doesNotThrowAnyException();
    }
}
