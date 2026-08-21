package com.freshmarket.product;

// 옵션의 품절 여부가 바뀌었다는 사실. stock이 발행하고, product가 구독해 자기 옵션 데이터에 반영한다.
public record OptionAvailabilityChangedEvent(Long productOptionId, boolean soldOut) {
}
