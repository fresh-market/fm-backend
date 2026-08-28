package com.freshmarket.coupon.domain;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 선착순 발급이 쓰는 회로 둘의 설정이다. 처리량을 맞추는 값이 아니라 <b>언제 포기할지</b>를
 * 정하는 값이라 발급 조율 값과 따로 둔다.
 *
 * <p>둘은 세는 것이 다르고 막는 것이 같다.
 *
 * <pre>
 * seq     Redis 호출 실패를 센다.  순번 확보를 막는다
 * write   DB 쓰기 실패를 센다.    역시 순번 확보를 막는다
 * </pre>
 *
 * <p>둘 다 플러시 스레드 자체는 안 막는다. 플러시를 막으면 <b>이미 큐에 들어간 요청까지 버리게
 * 된다</b>({@code docs/coupon/coupon.md} 3장).
 *
 * @param seq   Redis 가 답하지 않을 때 여는 회로
 * @param write DB 쓰기가 실패할 때 여는 회로. DB 가 죽어도 Redis 는 멀쩡해 저쪽이 안 열린다
 */
@ConfigurationProperties("coupon.circuit")
public record CouponCircuitProperties(@DefaultValue Settings seq, @DefaultValue Settings write) {

    /**
     * @param failureRateThreshold 이 비율(%)을 넘으면 회로가 열린다
     * @param slidingWindowSize    최근 몇 건으로 그 비율을 재나. 건수 기준이라 유입량에 안 휘둘린다
     * @param minimumNumberOfCalls 이만큼 쌓이기 전에는 비율을 안 잰다. 초기 몇 건으로 열리는 것을 막는다
     * @param waitDurationInOpen   열린 뒤 이만큼은 아무도 안 보낸다
     * @param permittedInHalfOpen  반열림에서 통과시켜 보는 건수. 이것들이 성공하면 회로가 닫힌다
     * @param slowCallDuration     이보다 오래 걸린 호출은 실패로 센다
     */
    public record Settings(
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("100") int slidingWindowSize,
            @DefaultValue("20") int minimumNumberOfCalls,
            @DefaultValue("10s") Duration waitDurationInOpen,
            @DefaultValue("5") int permittedInHalfOpen,
            @DefaultValue("500ms") Duration slowCallDuration) {
    }
}
