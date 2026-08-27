package com.freshmarket.coupon.domain.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 순번 확보에 거는 회로의 설정이다. 처리량을 맞추는 값이 아니라 <b>언제 포기할지</b>를 정하는
 * 값이라 발급 조율 값과 따로 둔다.
 *
 * @param failureRateThreshold 이 비율(%)을 넘으면 회로가 열린다
 * @param slidingWindowSize    최근 몇 건으로 그 비율을 재나. 건수 기준이라 유입량에 안 휘둘린다
 * @param minimumNumberOfCalls 이만큼 쌓이기 전에는 비율을 안 잰다. 초기 몇 건으로 열리는 것을 막는다
 * @param waitDurationInOpen   열린 뒤 이만큼은 아무도 안 보낸다
 * @param permittedInHalfOpen  반열림에서 통과시켜 보는 건수. 이것들이 성공하면 회로가 닫힌다
 * @param slowCallDuration     이보다 오래 걸린 호출은 실패로 센다
 */
@ConfigurationProperties("coupon.circuit")
public record CouponSeqCircuitProperties(
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("100") int slidingWindowSize,
        @DefaultValue("20") int minimumNumberOfCalls,
        @DefaultValue("10s") Duration waitDurationInOpen,
        @DefaultValue("5") int permittedInHalfOpen,
        @DefaultValue("500ms") Duration slowCallDuration) {
}
