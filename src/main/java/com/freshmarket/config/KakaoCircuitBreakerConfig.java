package com.freshmarket.config;

import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * kakaoLogin/kakaoLogout/kakaoUnlink 세 서킷이 공통으로 쓰는 "이 예외를 실패로 셀지" 판단.
 *
 * KakaoUnlinkClient 등은 원인을 MemberException/AuthException으로 감싸서 던지므로,
 * application.yml의 recordExceptions(클래스 목록)만으로는 카카오 응답 상태코드까지 못 본다 —
 * cause 체인을 직접 풀어서 판단해야 한다.
 *
 * 이 판단 로직을 member.domain 쪽으로 빼서 단위 테스트하려던 시도는 하지 않는다 —
 * ArchitectureTest.도메인_내부는_다른_도메인에_닫혀_있다가 config 슬라이스에서 member 슬라이스
 * 내부(domain.client)로의 의존을 막는다(공통/config로 나가는 의존만 예외). 그래서 이 predicate는
 * 여기 그대로 두고, 실제 CircuitBreakerConfig 빌더에 적용된 뒤의 동작은
 * KakaoCircuitBreakerIntegrationTest(src/integrationTest)가 CircuitBreakerRegistry에서 실제
 * predicate를 꺼내 검증한다 — WebClient를 목킹할 필요도 없다.
 *
 * CircuitBreakerConfigCustomizer.of()는 인스턴스 하나당 커스터마이저 하나를 받는 구조라
 * (List로 한 번에 못 묶는다) 세 인스턴스마다 빈을 따로 등록한다 — 등록된 빈들은 Boot
 * 자동구성이 이름으로 매칭해서 각자의 인스턴스에 적용한다.
 */
@Configuration
public class KakaoCircuitBreakerConfig {

    @Bean
    public CircuitBreakerConfigCustomizer kakaoLoginFailureClassifier() {
        return CircuitBreakerConfigCustomizer.of("kakaoLogin", builder -> builder.recordException(FAILURE_PREDICATE));
    }

    @Bean
    public CircuitBreakerConfigCustomizer kakaoLogoutFailureClassifier() {
        return CircuitBreakerConfigCustomizer.of("kakaoLogout", builder -> builder.recordException(FAILURE_PREDICATE));
    }

    @Bean
    public CircuitBreakerConfigCustomizer kakaoUnlinkFailureClassifier() {
        return CircuitBreakerConfigCustomizer.of("kakaoUnlink", builder -> builder.recordException(FAILURE_PREDICATE));
    }

    /**
     * 5xx, 응답 자체를 못 받은 경우(타임아웃/커넥션 거부/DNS), 429는 서킷 실패로 센다.
     * 429는 Admin Key 단위 앱 전체 쿼터라 유저별 문제가 아니다 — 계속 불러봐야 더 막힐
     * 뿐이라 5xx와 똑같이 취급해 서킷을 연다. 그 외 4xx(400/401/403/404 등)는 카카오가
     * "정상적으로 거절한" 응답이라 재시도해도 똑같이 실패하므로 서킷 실패로 세지 않는다
     * — 카운트하면 우리 쪽 설정 실수(예: 잘못된 요청 파라미터) 하나로 무관한 다른 요청까지
     * 서킷에 막혀버린다.
     *
     * (2026-08-27, PR 리뷰 P1) 예전엔 "상태코드가 없으면 무조건 실패"로 셌다 — 그러면
     * timeout/DNS/connection 오류뿐 아니라 우리 코드의 NPE, DTO 매핑 버그, 잘못된 내부
     * 설정 같은 것도 전부 "카카오 장애"로 잘못 집계된다. 지금은 스프링이 실제로 전송
     * 단계(연결 실패/응답 못 받음/응답 타임아웃)에서 던지는 예외를 통째로 감싸는
     * WebClientRequestException(및 흔치 않게 그 아래 남아있는 TimeoutException)만
     * "응답 자체를 못 받은 경우"로 인정한다 — 그 외의, 원인을 알 수 없는 예외는 우리 쪽
     * 버그일 가능성이 더 높다고 보고 서킷 실패로 세지 않는다.
     */
    private static final Predicate<Throwable> FAILURE_PREDICATE = KakaoCircuitBreakerConfig::isCircuitFailure;

    private static boolean isCircuitFailure(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException wcre) {
                HttpStatusCode status = wcre.getStatusCode();
                return status.value() == 429 || status.is5xxServerError();
            }
            if (cause instanceof WebClientRequestException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
