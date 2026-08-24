package com.freshmarket.product;

import java.time.LocalDateTime;

/*
 * 옵션의 품절 여부가 바뀌었다는 사실. stock이 발행하고, product가 구독해 자기 옵션 데이터에 반영한다.
 *
 * occurredAt은 이 사실이 원래 발생한 시각이다(재시도/재발행 시각이 아니다) — 아웃박스가 나중에
 * 같은 이벤트를 재시도할 때도 이 값을 그대로 실어 보내야, product 쪽 조건부 UPDATE가 "이 사실보다
 * 더 최신 사실이 이미 반영됐는지"를 올바르게 비교할 수 있다(DI-2-01).
 */
public record OptionAvailabilityChangedEvent(Long productOptionId, boolean soldOut, LocalDateTime occurredAt) {
}
