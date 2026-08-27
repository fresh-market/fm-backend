package com.freshmarket.config;

import com.freshmarket.common.logging.ExternalApiLoggingExchangeFilter;
import com.freshmarket.common.logging.TraceIdExchangeFilter;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebClientConfig {

    // 외부 API 호출에 대한 1차 방어선. 연결 자체가 안 되는 상황(네트워크/DNS)과 연결은 됐는데
    // 응답이 안 오는 상황을 각각 다른 타임아웃으로 잡는다 — 커넥션 타임아웃만 있으면 "연결은
    // 됐지만 응답을 영원히 안 주는" 외부 API 장애를 못 막는다. 값은 보수적으로 잡았다(카카오
    // 기준 SLA 문서 없음) — 실제 운영 지연 분포를 보고 좁혀도 된다.
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    // 풀 자체가 꽉 찼을 때 대기하는 시간의 상한. 이게 없으면 connect/response 타임아웃을 걸어도
    // ".block()이 커넥션 풀에서 여유 커넥션을 기다리는 동안"은 방어가 안 된다 — 이 프로젝트는
    // WebClient 응답을 서블릿 스레드에서 .block()으로 기다리므로, 풀 대기가 길어지면 그 스레드도
    // 그만큼 길게 묶인다. maxConnections는 아직 실측 트래픽이 없어 보수적인 추정치이고, 진짜
    // 벤더별 풀 격리(벌크헤드)는 별도 작업으로 다룬다 — 이건 그 전 단계의 최소 방어선이다.
    private static final int MAX_CONNECTIONS = 50;
    private static final Duration PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(2);

    /**
     * TraceIdExchangeFilter를 붙여뒀지만 카카오는 우리 traceId 규약을 모르니 지금은 사실상
     * 무효과다(TraceIdExchangeFilter의 클래스 주석 참고) — 나중에 우리가 만든 다른 서비스를
     * WebClient로 호출하게 되면 그때부터 값어치가 생긴다.
     *
     * ExternalApiLoggingExchangeFilter는 반대로 지금 당장도 값어치가 있다 — 여기서 나가는 모든
     * 호출의 상태코드/소요시간이 자동으로 남는다.
     *
     * 이 커스터마이저를 우회하는 유일한 경로는 벤더별 빈이 주입받은 builder를 안 쓰고
     * WebClient.builder()를 직접 호출하는 것이다 — 그러면 타임아웃/풀 설정 없이 .block()을
     * 쓰는 것과 같아서, 상대가 응답을 안 주는 순간 그 요청 스레드가 무기한 묶인다.
     * ArchitectureTest의 WebClient는_직접_생성하지_않는다 규칙이 이 우회를 빌드에서 강제로 막는다.
     */
    @Bean
    public WebClientCustomizer defaultExternalApiCustomizer() {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("external-api")
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireTimeout(PENDING_ACQUIRE_TIMEOUT)
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);

        return builder -> builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(TraceIdExchangeFilter.propagateTraceId())
                .filter(ExternalApiLoggingExchangeFilter.logCalls());
    }

    /**
     * 벤더 계층. 카카오 unlink/logout/OIDC 토큰교환 호출 전용
     * (KakaoLogoutClient, KakaoUnlinkClient, KakaoIdTokenExchanger가 주입받아 쓴다).
     * 공통 타임아웃/풀/필터는 위 defaultExternalApiCustomizer가 이미 적용한 builder를 받아서 시작하므로
     * 여기선 카카오 고유의 것만 얹는다 — 지금은 얹을 게 없어서 build()만 호출한다.
     *
     * 카카오가 인증(kauth.kakao.com)과 API(kapi.kakao.com) 호스트
     * 자체를 분리해놔서 WebClient 하나에 baseUrl 하나로 못 묶는다.
     * 그래서 각 호출부가 지금처럼 절대경로 URI를 직접 준다.
     *
     * 나중에 카카오만 다른 속성이 필요해지면(예: logout/unlink 공통 Admin Key 헤더를 매번
     * 반복해서 붙이는 대신 여기 기본값으로 박아두거나, 로그인 흐름 UX 때문에 토큰교환만 공통
     * 5초보다 짧은 타임아웃을 원한다거나) 이 메서드의 체인을 늘리면 된다. 예:
     *
     *   return builder
     *           .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
     *           .clientConnector(new ReactorClientHttpConnector(
     *                   HttpClient.create().responseTimeout(Duration.ofSeconds(3))))
     *           .build();
     *
     * clientConnector를 여기서 다시 지정하면 공통 커스터마이저가 걸어둔 connector(타임아웃/풀)만
     * 덮어쓰는 것이고, 트레이싱/로깅 필터는 builder에 이미 filter()로 붙어있어서 그대로
     * 유지된다 — 필터까지 다시 정의할 필요는 없다.
     */
    @Bean
    public WebClient kakaoApiWebClient(WebClient.Builder builder) {
        return builder.build();
    }
}
