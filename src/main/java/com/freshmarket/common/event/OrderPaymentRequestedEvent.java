package com.freshmarket.common.event;

/*
 * order가 결제를 요청할 때 발행하는 중립 이벤트다.
 *
 * order와 payment는 둘 다 도메인 계층상 L2라 서로 직접 부를 수 없다
 * (ArchitectureTest.도메인은_아래로만_부른다: L2는 "아무도 부르지 못한다"이고, 이건 다른 L2
 * 도메인도 예외가 아니다). payment.PaymentApi 자체 주석은 "order가 결제를 시작할 때 쓰는 창구"라고
 * 되어 있지만, order가 그 인터페이스를 import하는 순간 빌드가 깨진다 — payment-api 브랜치의
 * 마지막 커밋 메시지도 "order->payment 직접호출 불가"라고 스스로 확인해 두었다.
 *
 * 그래서 이 이벤트는 어느 도메인 패키지에도 속하지 않는 common.event에 둔다. common은 두 규칙
 * 모두에서 예외로 빠져 있어(도메인_내부는_다른_도메인에_닫혀_있다의 ignoreDependency, 그리고
 * 도메인은_아래로만_부른다가 정의하지 않은 층이라 consideringOnlyDependenciesInLayers 대상 밖),
 * order와 payment 양쪽 모두 여기 있는 타입은 자유롭게 참조할 수 있다.
 *
 * amount만 담고 결제수단(PaymentMethod)은 담지 않는다 — PaymentMethod는 payment 도메인
 * 루트(L2)에 있어 order가 이 이벤트 안에서조차 그 타입을 참조하면 같은 규칙에 걸린다. 결제수단
 * 선택은 아직 API 범위 밖(mock 결제만 있는 지금 단계)이라, 그 값은 이 이벤트를 받는 payment 쪽
 * 리스너가 자기 도메인 안에서 정한다.
 */
public record OrderPaymentRequestedEvent(
        Long orderId,
        int amount
) {
}
