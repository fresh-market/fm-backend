package com.freshmarket.common.logging;

import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

/**
 * 아웃바운드 WebClient 요청에 현재 처리 중인 요청의 traceId를 X-Trace-Id 헤더로 실어 보낸다.
 * MdcLoggingFilter가 들어온 요청에 채워둔 MDC 값을 그대로 재사용한다.
 *
 * === 지금 이 클래스는 실제로 쓰는 WebClient가 없어 사실상 아무 효과가 없다 — 의도적으로
 * 미리 만들어둔 것뿐이다 ===
 *
 * traceId 전파가 실제로 값어치를 가지려면 "보내는 쪽"과 "받는 쪽"이 같은 규약(이 값을 헤더로 주고받고,
 * 받은 쪽은 그 값을 자기 로그에도 그대로 남긴다)을 공유해야 한다. 앞으로 붙일 외부 API 클라이언트가
 * 카카오처럼 우리가 정한 X-Trace-Id라는 사설 헤더를 모르는 서비스라면, 보내봐야 받는 쪽에서
 * 버려질 뿐이다.
 *
 * 이게 진짜 의미가 생기는 시점은 이 서비스가 "우리가 직접 만든 다른 서비스"를 호출하게 될 때다 —
 * 그 서비스도 MdcLoggingFilter 같은 걸 갖고 있어서 들어온 요청의 X-Trace-Id를 그대로 이어받아 쓰면,
 * 그때부터 여러 서비스의 로그가 하나의 traceId로 묶인다. 지금은 모놀리스라 그 "다른 서비스"가 없어서
 * 당장 쓸모는 없지만, 비용이 거의 없고(로그·트래픽에 미치는 영향 없음) 나중에 서비스가 늘어나는 순간
 * 바로 쓸 수 있게 미리 만들어둔 것.
 *
 * === OpenTelemetry 같은 정식 분산 추적과는 다르다 ===
 *
 * 이건 그냥 문자열 하나를 그대로 복사해서 다음 요청에 실어 보내는 "간이(simplified)" 구현이다.
 * span 트리(어느 호출이 어느 호출의 자식인지 인과관계 기록), 자동 계측(HTTP/DB/Redis 호출마다 자동으로
 * 구간을 만들어줌), 표준 헤더 포맷(W3C Trace Context의 traceparent — 외부 서비스가 이미 이해하고
 * 있을 가능성이 있음) 같은 건 전혀 없다. 정말 여러 서비스에 걸친 지연시간 분석·장애 지점 추적까지
 * 필요해지면 이 필터를 걷어내고 OpenTelemetry 에이전트/SDK로 옮기는 게 맞다.
 *
 * === 스레드 관련 주의 ===
 *
 * MDC는 스레드로컬이라, 이 필터가 원래 요청을 받은 서블릿 스레드와 다른 스레드에서 실행되면(예: 이
 * WebClient 호출을 진짜 비동기로 구독하거나 별도 Scheduler를 태우면) MDC 값이 안 보일 수 있다. 외부
 * API 클라이언트를 .block()으로 동기 호출하는 동안은 스레드가 안 바뀌니 문제없지만, 나중에 리액티브
 * 방식으로 바꾸면 Reactor Context 기반 전파로 갈아타야 한다.
 */
public final class TraceIdExchangeFilter {

    private TraceIdExchangeFilter() {
    }

    public static ExchangeFilterFunction propagateTraceId() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            String traceId = MDC.get(MdcLoggingFilter.TRACE_ID);
            if (traceId == null) {
                return Mono.just(request);
            }
            return Mono.just(ClientRequest.from(request)
                    .header(MdcLoggingFilter.TRACE_ID_HEADER, traceId)
                    .build());
        });
    }
}
