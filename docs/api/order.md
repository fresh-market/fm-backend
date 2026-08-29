# 주문

장바구니부터 결제, 취소, 반품, 교환, 배송까지다. 재고 변동은 [stock.md](./stock.md) 를 함께 본다.

## 장바구니

```
GET    /v1/carts/me
POST   /v1/carts/me/items
PATCH  /v1/carts/me/items/{cartItemId}
DELETE /v1/carts/me/items/{cartItemId}
```

회원당 하나라 싱글톤이다 (`API-2-07`). DB 가 `uk_cart_member` 로 1인 1카트를 강제한다.

**생성 API 가 없다. 회원가입 때 함께 만들어진다.** 카카오 최초 로그인으로 회원 행이 생길 때
같은 트랜잭션에서 장바구니도 만든다.

그래서 담기 경로에 **"없으면 만든다" 분기가 없고** 조회는 항상 성공한다. 비어 있을 뿐이다.
조회가 행을 만들지 않으므로 `GET` 이 부수 효과를 갖지 않고 (`API-3-03`), 나중에 읽기 복제본으로
보낼 수도 있다.

```json
{ "cartId": 9, "items": [], "totalQty": 0 }
```

담을 때는 옵션과 수량만 보낸다.

```json
{ "productOptionId": 31, "qty": 2 }
```

**같은 옵션을 다시 담으면 수량이 더해진다.** DB 가 `(cart_id, product_option_id)` UNIQUE 로
중복 행을 막는다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `422` | `CART-001` | 가용 재고가 0이다 |
| `422` | `CART-002` | 수량이 1 미만이다 |

## 주문

### 생성

```
POST /v1/orders
```

```json
{
  "requestId": "f5f3d14a-2a9b-4f3f-a46d-d2a2d5f83575",
  "items": [ { "productOptionId": 31, "qty": 2 } ],
  "addressId": 55,
  "shipMessage": "부재 시 경비실"
}
```

장바구니 주문과 바로구매 중 하나만 선택한다.

```json
{ "requestId": "...", "cartItemIds": [101, 102], "addressId": 55 }
```

```json
{ "requestId": "...", "items": [{ "productOptionId": 31, "qty": 2 }], "addressId": 55 }
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `requestId` | O | 재시도와 중복 클릭을 같은 주문으로 수렴시키는 식별자 |
| `cartItemIds` | 조건부 | 장바구니 주문 대상. `items`와 함께 보낼 수 없다 |
| `items` | 조건부 | 바로구매 옵션과 수량. `cartItemIds`와 함께 보낼 수 없다 |
| `addressId` | O | 회원 소유의 저장된 배송지. 주문에는 배송지 정보를 스냅샷으로 저장한다 |

장바구니를 거치지 않는 바로구매도 같은 경로다. `items` 에 한 건 이상 넣으면 된다.

**주문 시점의 상품명, 옵션명, 가격을 스냅샷으로 저장한다.** 나중에 상품이 바뀌어도 주문 내역은
그대로 남는다.

```json
{
  "orderId": 3012,
  "orderNo": "20260817-000312",
  "status": "PAYMENT_PENDING",
  "productAmount": 25800,
  "discountAmount": 3000,
  "shippingFee": 3000,
  "totalAmount": 25800,
  "items": [ { "orderItemId": 501, "nameSnapshot": "제주 감귤 1kg", "unitPrice": 12900, "qty": 2 } ]
}
```

**금액이 서로 맞는지 DB 가 강제한다.**

```
totalAmount = productAmount - discountAmount + shippingFee
discountAmount <= productAmount
```

생성 시점에 재고를 **예약**한다 (`RESERVE`). 결제 전이라도 남이 가져가지 못한다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `409` | `ORDER-001` | 재고 부족. **어떤 옵션인지 `data` 에 담아 준다** |
| `422` | `ORDER-002` | 배송지 누락 |
| `409` | `ORDER-003` | 쿠폰을 이미 다른 주문에 썼다 |
| `422` | `ORDER-004` | 쿠폰 사용 조건 미달 (최소 주문 금액) |

### 결제

```
POST /v1/orders/{orderId}/payments
```

```json
{ "method": "CARD" }
```

| `method` | |
|---|---|
| `CARD` | 카드 |
| `EASY_PAY` | 간편결제 |

PG 결제창으로 보낼 정보를 돌려주고, 결과는 웹훅으로 받는다.

```
POST /v1/payments/webhooks/pg
```

**웹훅은 본문을 검증하고 멱등하게 처리한다** (`DPB-3-05`, `DPB-3-06`). 재시도가 오고
같은 상태 변경이 여러 번 도착할 수 있다.

결제가 완료되면 예약을 **확정**으로 바꾸고 (`CONFIRM`) 주문 상태가 `PAID` 가 된다.

**총액이 0원인 주문은 결제 행을 만들지 않고 바로 `PAID` 가 된다.** 할인이 상품 금액과 배송비를
모두 덮는 경우다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `409` | `PAYMENT-001` | 이미 결제된 주문 |
| `422` | `PAYMENT-002` | 결제 금액이 주문 총액과 다르다 |

### 내 주문 목록과 상세

```
GET /v1/orders?status={상태}&from={날짜}&to={날짜}
GET /v1/orders/{orderId}
```

주문 일시 내림차순이 기본이다. 3개월, 6개월 필터를 쓴다.
**목록 쿼리에 소유자 조건이 들어간다** (`SEC-1-03`).

상세는 주문 시점 가격 기준으로 상품, 수량, 금액, 결제수단, 배송지, 배송 상태를 준다.

## 상태

```
PAYMENT_PENDING -> PAID -> PRODUCT_PREPARING -> SHIPMENT_PREPARING -> SHIPPING -> DELIVERED
                                                                                 ├-> CONFIRMED
                                                                                 ├-> RETURN_REQUESTED -> RETURNED
                                                                                 └-> EXCHANGE_REQUESTED -> EXCHANGED
```

| 상태 | 회원이 할 수 있는 것 |
|---|---|
| `PAYMENT_PENDING` | 취소 |
| `PAID` | 취소 |
| `PRODUCT_PREPARING` | **취소. 회원이 직접 취소할 수 있는 마지막 단계다** |
| `SHIPMENT_PREPARING` 이후 | 직접 취소 불가. 고객센터를 거친다 |
| `DELIVERED` | 반품 신청, 교환 신청, 구매확정 |
| `CONFIRMED` | 없음. 쿠폰 사용이 확정된다 |

**`CANCELED` 와 `RETURNED` 는 주문 전체에만 쓴다.** 취소는 라인 단위로 일어나지 않고,
부분 반품은 헤더를 바꾸지 않고 라인만 바꾼다.

## 클레임

### 주문 취소

```
POST /v1/orders/{orderId}:cancel
```

```json
{ "reason": "단순 변심" }
```

취소하면 **자동 환불과 재고 복원(`RELEASE`)이 함께 일어난다.** 사용한 쿠폰은 미사용으로 돌아간다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `409` | `ORDER-005` | 배송준비 이후 상태다. 고객센터를 거쳐야 한다 |
| `409` | `ORDER-006` | 이미 취소된 주문 |

### 반품 신청

```
POST /v1/orders/{orderId}:requestReturn
```

```json
{
  "orderItemIds": [501],
  "reasonType": "DEFECT",
  "reason": "일부가 물러 있었습니다",
  "attachmentUploadIds": ["018f..."]
}
```

| `reasonType` | 배송비 |
|---|---|
| `CHANGE_OF_MIND` | 차감 후 환불 |
| `DEFECT` | 전액 환불 |

**배송완료 후 7일 안에만 신청할 수 있다.** 라인 단위로 고르며 부분 수량은 지정하지 않는다.

### 교환 신청

```
POST /v1/orders/{orderId}:requestExchange
```

**동일 상품, 동일 옵션만 교환한다.** 다른 상품은 반품 후 재주문이다.

결제 금액이 바뀌지 않으므로 **쿠폰은 복원하지 않고 유지한다.**

| 오류 | 코드 | 언제 |
|---|---|---|
| `409` | `CLAIM-001` | 배송완료 후 7일이 지났다 |
| `409` | `CLAIM-002` | 같은 주문에 반품과 교환을 동시에 신청할 수 없다 |
| `409` | `CLAIM-003` | 구매확정 건이다 |
| `422` | `CLAIM-004` | 교환할 재고가 없다. 반품으로 유도한다 |

### 구매확정

```
POST /v1/orders/{orderId}:confirm
```

배송완료 7일 후 자동으로도 확정된다.

## 관리자

### 주문 관리

```
GET  /v1/admin/orders?status=&from=&to=&query=
POST /v1/admin/orders/{orderId}:prepareProduct
POST /v1/admin/orders/{orderId}:prepareShipment
POST /v1/admin/orders/{orderId}:ship
POST /v1/admin/orders/{orderId}:deliver
POST /v1/admin/orders/{orderId}:cancel
```

`:ship` 은 송장을 받는다.

```json
{ "carrier": "CJ대한통운", "trackingNo": "123456789012" }
```

**배송은 주문당 하나다.** 분할 배송을 하지 않으므로 주문의 모든 라인이 한 배송에 실린다.

배송 상태와 시각이 따로 놀지 않도록 DB 가 CHECK 로 묶는다.

| 배송 상태 | `shippedAt` | `deliveredAt` |
|---|---|---|
| `PREPARING` | 없음 | 없음 |
| `SHIPPING` | 있음 | 없음 |
| `DELIVERED` | 있음 | 있음 |

### 클레임 승인과 거절

```
GET  /v1/admin/claims?type=&status=
POST /v1/admin/claims/{claimId}:approve
POST /v1/admin/claims/{claimId}:reject
```

```json
{ "reason": "상품 하자 확인", "restockable": true }
```

**승인 시 회수품 처리가 갈린다.**

```
잔여 소비기한 >= 기준일   RESTOCK   원래 로트로 복원
잔여 소비기한 <  기준일   DISPOSE   폐기 등록, 폐기율 통계에 반영
파손 등 재판매 불가       DISPOSE   잔여일과 무관하게 폐기
```

**판정 기준일은 관리자 승인 시점이다.**

반품 승인이면 환불이 함께 만들어진다. 환불액은 **배송비 차감 후 실지급액**이다.

```json
{ "amount": 22800, "shippingDeduction": 3000 }
```

`payment.refunded_amount` 가 환불 총액의 상한을 쥔다. 결제액을 넘는 환불은 DB 가 막는다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `409` | `CLAIM-005` | 이미 처리된 클레임 |
| `422` | `CLAIM-006` | 환불액이 결제 잔액을 넘는다 |

### 배송 사진

```
POST /v1/admin/shipments/{shipmentId}/photos:createUploadUrl
POST /v1/admin/shipments/{shipmentId}/photos/{photoId}:confirm
```

문앞 배송 완료 사진이다. **비공개다.** 업로드 방식은 상품 이미지와 같다.

## 정하지 못한 것

**배송비 정책이 없다.** N원 이상 무료, 도서산간 추가 같은 규칙이 정해지지 않았다.
지금은 `orders.shipping_fee` 에 계산된 값을 넣을 뿐이고, 계산 규칙을 어디에 둘지 결정이 필요하다.
