package com.freshmarket.config;

import com.freshmarket.common.logging.ExternalApiLoggingExchangeFilter;
import com.freshmarket.common.logging.TraceIdExchangeFilter;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    // 연결 자체가 안 되는 상황(네트워크/DNS)과 연결은 됐는데 응답이 안 오는 상황을 각각 다른
    // 타임아웃으로 잡는다 — 커넥션 타임아웃만 있으면 "연결은 됐지만 응답을 영원히 안 주는" 카카오
    // 장애를 못 막는다. 값은 보수적으로 잡았다(카카오 SLA 문서 없음) — 실제 운영 지연 분포를
    // 보고 좁혀도 된다.
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 카카오 unlink/logout API 호출 전용. 다른 외부 API용 WebClient가 있다면 그것과 분리해서 쓰는 걸 권장.
     *
     * TraceIdExchangeFilter를 붙여뒀지만 카카오는 우리 traceId 규약을 모르니 지금은 사실상 무효과다
     * (TraceIdExchangeFilter의 클래스 주석 참고) — 나중에 우리가 만든 다른 서비스를 이 WebClient류로
     * 호출하게 되면 그때부터 값어치가 생긴다.
     *
     * ExternalApiLoggingExchangeFilter는 반대로 지금 당장도 값어치가 있다 — 이 WebClient로 나가는
     * 모든 호출(지금은 카카오, 나중에 다른 외부 API가 추가돼도)의 상태코드/소요시간이 자동으로 남는다.
     * 새 외부 API용 WebClient를 추가할 땐 이 필터도 같이 붙이는 걸 잊지 말 것.
     *
     * 타임아웃 없이 .block()으로 쓰면(KakaoLogoutClient/KakaoUnlinkClient/카카오 OIDC 교환 전부)
     * 카카오가 응답을 안 주는 순간 그 요청 스레드가 무기한 묶인다 — connectTimeout/responseTimeout을
     * 반드시 준다.
     */
    @Bean
    public WebClient kakaoApiWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(TraceIdExchangeFilter.propagateTraceId())
                .filter(ExternalApiLoggingExchangeFilter.logCalls())
                .build();
    }
}
