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

  **[2026-09-06 KST] PAYMENT_PENDING 만료 스케줄러 추가 + 만료-뒤늦은승인 충돌 정책**:
  `order.internal.batch.PendingOrderExpirationService`/`PendingOrderExpirationScheduler`가
  PG에 물어볼 거래 자체가 없을 만큼(기본 60분, `order.payment-expiration.grace-minutes`) 오래
  PAYMENT_PENDING에 멈춘 주문을 취소한다 — orderdevelopmenthandoff.txt 9번 항목의 "순수 TTL
  기반 만료 스케줄러". `payment.internal.batch.PaymentReconciliationService`(UNKNOWN/PENDING
  재조회)와는 별개로, PG가 아예 답할 거래가 없는 경우를 시간으로 포기하는 배치다.

  이 배치가 먼저 주문을 CANCELED로 확정한 뒤 뒤늦게 PG 승인이 도착하면
  `OrderCreateService.onPaymentApproved()`가 이를 감지해 order는 되돌리지 않고(재고가 이미
  release()로 풀려 다른 주문에 재배분됐을 수 있어 안전하지 않음) `PAYMENT_APPROVED_AFTER_ORDER_
  CANCELED` ERROR 로그만 남긴다 — PG는 실제로 승인했으므로(돈이 이미 나갔으므로) 사람이 이 로그로
  수동 환불해야 한다(`OrderCreateService`에 `TODO: 자동 환불 로직` 표시, `PaymentApi`에 환불
  계약이 아직 없어서다). 반대 방향(만료 뒤 뒤늦은 거절/타임아웃)은 `Order.cancel()`/
  `StockReservationService.release()`의 기존 멱등 가드가 그대로 흡수하므로 별도 분기가 없다.

  두 배치가 같은 주문을 동시에 건드릴 수 있어(주문 인수인계 문서 5번 섹션 "상태 전이 경쟁"에 이미
  지적돼 있던 지점) `OrderRepository.findByIdForUpdate`(신규, `PaymentRepository`의 같은 이름
  메서드와 동일한 패턴)로 잠근 뒤 상태를 다시 확인하게 했다 — `Order`에는 `@Version`이 없어서
  락 없이는 나중에 커밋하는 쪽이 그냥 덮어쓰는 lost update가 가능했다.

  **[2026-09-06 KST] R02 보류 중 알려진 갭 — 재고 확정/해제 결과가 order에게 전달 안 되는 경우**:
  `OrderCreateService.onPaymentApproved`/`onPaymentFailed` 안에서 `order.markPaid()`/
  `order.cancel()`과 `stockApi.confirm()`/`stockApi.release()`가 같은 트랜잭션에 있어서, 재고
  쪽이 실패하면 order 상태 변경까지 통째로 롤백된다 — "주문은 PAID인데 재고는 미확정" 같은
  절반짜리 상태는 안 생긴다. 진짜 문제는 그다음이다: 이 트랜잭션 자체가 실패하고 나면 Payment는
  이미 별도 트랜잭션에서 PAID/FAILED로 커밋 완료된 상태인데 order만 그 사실을 놓친 채
  PAYMENT_PENDING에 멈추고, 이걸 다시 집어서 재시도해줄 장치가 지금 없다 — 이 이벤트 체인 전달
  자체가 스프링 인메모리 이벤트(`ApplicationEventPublisher`)에만 의존하기 때문이다.

  정석적인 해법은 아웃박스 패턴(Payment.approve()/fail()이 커밋되는 트랜잭션 안에 전달할 이벤트를
  아웃박스 테이블에 같이 쓰고, 별도 폴러가 order 쪽 전달을 성공할 때까지 재시도)인데, 이건 결국
  "order-payment가 서로 신뢰성 있게 어떻게 통신할 것인가"라는 R02 본론과 같은 자리의 문제라 R02보다
  먼저 정식 아웃박스를 놓는 건 이르다고 보고 지금은 손대지 않기로 했다. 필요해지면 정식 아웃박스
  대신 "Payment는 PAID/FAILED로 확정됐는데 대응하는 Order는 아직 PAYMENT_PENDING인 것"을 훑는
  order 쪽 재확인 배치(payment.internal.batch.PaymentReconciliationService와 같은 패턴)로 임시로
  메우는 방법도 있다 — 다만 이것도 R02가 이 v1 이벤트 연결 자체를 다른 방식으로 바꿀 수 있으므로,
  R02 결정 이후 필요 여부를 다시 판단한다.

coupon 도메인은 아직 호출하지 않는다. 주문·주문 항목의 할인 금액은 0으로 고정하며,
`OrderPriceCalculator`의 TODO에서 후속 연동 지점을 관리한다.
