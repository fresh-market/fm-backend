package com.freshmarket.member.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.freshmarket.IntegrationTestSupport;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * (2026-08-27, PR 리뷰 P1) 단위 테스트로는 확인할 수 없는 세 가지를 실제 Spring 컨텍스트에서
 * 검증한다.
 *
 * 1. kakaoUnlink/kakaoLogout 빈이 실제로 @CircuitBreaker AOP 프록시를 거치는지(self-invocation
 *    구조라 프록시가 안 걸리는 버그가 카카오 로그인 쪽에 실제로 있었다 — KakaoIdTokenExchanger 참고).
 * 2. application.yml의 인스턴스 이름(kakaoUnlink/kakaoLogout)과 @CircuitBreaker(name=...)가
 *    실제로 같은 서킷을 가리키는지 — CircuitBreakerRegistry에서 이름으로 가져온 서킷을 OPEN으로
 *    돌렸을 때, 그 이름의 빈을 호출하면 실제로 막히는지로 확인한다(이름이 어긋나 있으면 이 테스트가
 *    실패한다 — 별도 이름의 새 서킷이 만들어지고 실제 프록시는 계속 CLOSED 상태로 남기 때문).
 * 3. OPEN 상태에서는 WebClient 호출 자체가 일어나지 않는지 — CallNotPermittedException은 AOP
 *    프록시가 메서드 본문에 들어가기도 전에 던지는 예외라, 이게 던져진다는 것 자체가 실제
 *    카카오 서버로 나가는 요청이 시도조차 안 됐다는 뜻이다(진짜로 나갔다면 admin-key가 가짜라
 *    401을 받거나 3초 connect timeout으로 MemberException이 났을 것이다).
 * 4. application.yml + KakaoCircuitBreakerConfig가 실제로 적용한 실패 판단 predicate가
 *    4xx는 실패로 안 세고 5xx/timeout/응답 자체를 못 받은 경우는 실패로 세는지 —
 *    KakaoCircuitBreakerConfig.isCircuitFailure()는 config 슬라이스에 있어서(member 도메인
 *    내부로 못 옮긴다 — ArchitectureTest.도메인_내부는_다른_도메인에_닫혀_있다) 여기서
 *    직접 단위 테스트할 수 없다. 대신 CircuitBreakerRegistry에 실제로 등록된 서킷에서
 *    predicate를 꺼내 검증한다 — WebClient를 목킹할 필요도 없다.
 */
@SpringBootTest
class KakaoCircuitBreakerIntegrationTest extends IntegrationTestSupport {

    private static final String KAKAO_UNLINK = "kakaoUnlink";
    private static final String KAKAO_LOGOUT = "kakaoLogout";

    @Autowired
    private KakaoUnlinkClient kakaoUnlinkClient;

    @Autowired
    private KakaoLogoutClient kakaoLogoutClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @AfterEach
    void resetCircuitBreakers() {
        // 이 컨텍스트는 IntegrationTestSupport 덕분에 다른 테스트 클래스와도 공유된다 — OPEN
        // 상태를 여기 남겨두면 뒤이어 도는 다른 테스트가 영향을 받는다.
        circuitBreakerRegistry.circuitBreaker(KAKAO_UNLINK).reset();
        circuitBreakerRegistry.circuitBreaker(KAKAO_LOGOUT).reset();
    }

    @Test
    void kakaoUnlink_kakaoLogout_빈은_CircuitBreaker_AOP_프록시로_감싸져_있다() {
        assertThat(AopUtils.isAopProxy(kakaoUnlinkClient)).isTrue();
        assertThat(AopUtils.isAopProxy(kakaoLogoutClient)).isTrue();
    }

    @Test
    void kakaoUnlink_서킷이_OPEN이면_실제_호출_없이_즉시_막힌다() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(KAKAO_UNLINK);
        circuitBreaker.transitionToOpenState();

        // 진짜로 네트워크까지 나갔다면 가짜 admin-key 탓에 401(MemberException)이 나거나
        // 3초 connect timeout이 걸렸을 것이다 — CallNotPermittedException이 즉시 나온다는 건
        // 프록시가 메서드 본문에 들어가기 전에 이미 막았다는 뜻이다.
        assertThatThrownBy(() -> kakaoUnlinkClient.unlink("kakao-circuit-breaker-test"))
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void kakaoLogout_서킷이_OPEN이면_실제_호출_없이_즉시_막힌다() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(KAKAO_LOGOUT);
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> kakaoLogoutClient.logout("kakao-circuit-breaker-test"))
                .isInstanceOf(CallNotPermittedException.class);
    }

    // ---- 실제 등록된 failure predicate 검증 (KakaoCircuitBreakerConfig.isCircuitFailure) ----

    private static WebClientResponseException responseException(int status) {
        return new WebClientResponseException(status, "status " + status, null, null, null);
    }

    private Predicate<Throwable> recordExceptionPredicate() {
        return circuitBreakerRegistry.circuitBreaker(KAKAO_UNLINK)
                .getCircuitBreakerConfig()
                .getRecordExceptionPredicate();
    }

    @Test
    void 서버_5xx와_429는_실패로_세고_나머지_4xx는_실패로_안_센다() {
        Predicate<Throwable> predicate = recordExceptionPredicate();

        assertThat(predicate.test(responseException(500))).isTrue();
        assertThat(predicate.test(responseException(429))).isTrue();
        assertThat(predicate.test(responseException(400))).isFalse();
        assertThat(predicate.test(responseException(401))).isFalse();
        assertThat(predicate.test(responseException(403))).isFalse();
        assertThat(predicate.test(responseException(404))).isFalse();
    }

    @Test
    void 응답_자체를_못_받은_경우는_실패로_센다() {
        Predicate<Throwable> predicate = recordExceptionPredicate();
        WebClientRequestException requestException = new WebClientRequestException(
                new IOException("connection refused"), HttpMethod.POST,
                URI.create("https://kapi.kakao.com/v1/user/unlink"), new HttpHeaders());

        assertThat(predicate.test(requestException)).isTrue();
        assertThat(predicate.test(new TimeoutException("read timeout"))).isTrue();
    }

    @Test
    void 원인을_알_수_없는_예외는_실패로_안_센다() {
        // 우리 쪽 버그(NPE, 매핑 오류 등)까지 카카오 장애로 잘못 집계되지 않는지 확인한다.
        Predicate<Throwable> predicate = recordExceptionPredicate();

        assertThat(predicate.test(new RuntimeException("버그 흉내", new NullPointerException()))).isFalse();
    }
}
