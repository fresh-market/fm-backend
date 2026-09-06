# 주문 생성 foundation 연동 대기 목록

이 브랜치는 외부 도메인 호출 없이 주문 생성의 입력·멱등성·엔티티 불변식·금액 계산만 준비한다.
아래 계약이 병합된 뒤 `OrderCreateService`와 `POST /v1/orders`를 연결한다.

## 사용할 공개 계약

- CartApi
  - `getCheckoutInfo(memberId, cartItemIds)`: 선택한 장바구니 항목의 소유권·수량을 확인하고 주문 스냅샷을 얻는다.
  - `removeCheckedOutItems(memberId, cartItemIds)`: 결제 완료 뒤 구매한 항목만 멱등 삭제한다.
- MemberApi
  - `findAddress(addressId, memberId)`: 회원 소유 배송지를 주문 배송지 스냅샷으로 복사한다.
- ProductApi
  - `findOptionInfos(productOptionIds)`: 상품명·옵션명·가격·판매 가능 여부를 다건 조회한다.
- StockApi (연동 완료)
  - `reserve(StockReservationRequest)`: 주문 항목 저장 후 재고를 예약한다.
  - `confirm(StockOrderItemsRequest)`: 결제 완료 뒤 예약을 확정한다. availableQty를 다시 차감하지 않는다.
  - `release(StockOrderItemsRequest)`: 결제 최종 실패 때 RESERVED 재고만 해제한다(연동 완료).
- Payment (연동 완료, v1)
  - 주문 생성 뒤 결제 요청 이벤트(`OrderPaymentRequestedEvent`)를 발행하고, `OrderPaymentApprovedEvent`
    수신 시 주문을 PAID로 전이하고 재고를 확정한다.
  - 결제가 최종 실패(FAILED)로 확정되면 `OrderPaymentFailedEvent`를 수신해 주문을 취소하고
    StockApi.release를 호출한다. PG 응답이 아직 불확실한 UNKNOWN 상태에서는 이 이벤트가 발행되지
    않는다 — 복구 배치가 나중에 PAID로 되돌릴 수도 있는 상태를 성급히 취소로 확정하면 안 되기
    때문이다(payment.internal.PaymentReconciliationService 참고).

  **[2026-09-05 19:13 KST] v1/v2 메모**: order/payment는 둘 다 L2 도메인이라 서로 직접 호출할 수
  없어(domain-package-boundary-guideline.md), 지금은 `common.event`에 중립 이벤트를 두고
  발행/구독하는 방식(v1)으로 이 연결을 구현했다. 이 방식이 맞는 경계 설계인지는 아직 팀 논의가
  안 끝났다(엔지니어링 리팩토링 로드맵의 R02: order-payment 도메인 경계 ADR, 보류 중) — R02가
  결정되면 이 이벤트 기반 연결(OrderPaymentRequestedEvent/OrderPaymentApprovedEvent/
  OrderPaymentFailedEvent 전부)을 그 결정에 맞게 다시 봐야 한다. R01(결제 실패·미확정·복구 상태
  머신)을 먼저 끝내기 위해 지금은 이 v1으로 간다.

coupon 도메인은 아직 호출하지 않는다. 주문·주문 항목의 할인 금액은 0으로 고정하며,
`OrderPriceCalculator`의 TODO에서 후속 연동 지점을 관리한다.
