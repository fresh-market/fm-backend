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
- StockApi (연동 대기)
  - `reserve(StockReservationRequest)`: 주문 항목 저장 후 재고를 예약한다.
  - `confirm(StockOrderItemsCommand)`: 결제 완료 뒤 예약을 확정한다. availableQty를 다시 차감하지 않는다.
  - `release(StockOrderItemsCommand)`: 결제 실패·취소·만료 때 RESERVED 재고만 해제한다.
- Payment (연동 대기)
  - 주문 생성 뒤 결제 요청 이벤트를 발행하고, `PaymentApprovedEvent` 수신 시 주문을 PAID로 전이한다.
  - 결제 실패 이벤트 계약이 확정되면 주문 취소와 StockApi.release를 연결한다.

coupon 도메인은 아직 호출하지 않는다. 주문·주문 항목의 할인 금액은 0으로 고정하며,
`OrderPriceCalculator`의 TODO에서 후속 연동 지점을 관리한다.
