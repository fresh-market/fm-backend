package com.freshmarket.common.logging;

import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * 아웃바운드 WebClient 호출(외부 API)마다 method/URL/상태코드/소요시간을 자동으로 남기는
 * 공통 필터. WebClient.Builder에 한 번 끼워두면, 그 WebClient로 나가는 모든 호출이 클라이언트
 * 클래스에서 로깅 코드를 따로 안 짜도 자동으로 기록된다 — 외부 API가 늘어나도 이 필터를 쓰는
 * WebClient에 얹기만 하면 됨.
 *
 * 각 외부 API 클라이언트가 스스로 남길 "이 요청이 왜 실패했는지"류 비즈니스 로그와는 역할이
 * 다르다 — 그쪽은 도메인 맥락(예: 어떤 사용자에 대한 요청이 왜 실패했는지)이고, 이 필터는
 * "이 HTTP 호출이 기술적으로 얼마나 걸렸고 상태코드가 뭐였는지"만 본다. 서로 보완 관계라
 * 어느 한쪽이 다른 쪽을 대체하지 않고 같이 남는다.
 *
 * 요청/응답 바디는 안 남긴다 — WebClient는 바디가 Publisher라 가로채려면 별도로 감싸야 해서
 * 복잡도가 커지고, 지금 목적(외부 API가 느려서 우리가 멈췄는지 증명하는 것)엔 상태코드+소요시간이면
 * 충분하다고 판단했다. URL은 그대로 로그에 남기므로, 쿼리 파라미터에 토큰/키 같은 민감정보를
 * 싣는 외부 API를 붙이게 되면 이 필터를 그대로 쓰지 말고 URL 마스킹을 추가해야 한다.
 */
@Slf4j
public final class ExternalApiLoggingExchangeFilter {

    private ExternalApiLoggingExchangeFilter() {
    }

    public static ExchangeFilterFunction logCalls() {
        return (request, next) -> {
            Instant start = Instant.now();
            // (SEC-4-02) 쿼리 문자열은 뺀다. 카카오처럼 인가 코드/액세스 토큰을 쿼리로 주고받는
            // 외부 API가 붙으면 그대로 새기 때문이다 — 이 필터의 목적(상태코드/소요시간 확인)엔
            // path만으로 충분하다.
            String path = request.url().getPath();
            return next.exchange(request)
                    .doOnNext(response -> log.info(
                            "event=EXTERNAL_API_CALL method={} path={} status={} durationMs={}",
                            request.method(), path, response.statusCode().value(),
                            Duration.between(start, Instant.now()).toMillis()))
                    // (MNT-4-03) ex.toString()만 마지막 "{}" 자리를 채우면 SLF4J가 이걸 그냥 문자열
                    // 인자로 취급해 스택트레이스가 로그에 안 남는다. err={}에는 요약 메시지를 남기고,
                    // ex 자체를 플레이스홀더 없는 마지막 인자로 추가로 넘겨야 스택트레이스까지 함께 찍힌다.
                    .doOnError(ex -> log.warn(
                            "event=EXTERNAL_API_CALL_FAILED method={} path={} durationMs={} err={}",
                            request.method(), path,
                            Duration.between(start, Instant.now()).toMillis(), ex.toString(), ex));
        };
    }
}
