# 재고

**전부 관리자 전용이다.** 회원에게는 품절 여부만 노출되고 수치는 나가지 않는다.

재고는 상품이 아니라 **로트(`stock_lot`) 단위**로 관리한다. 같은 상품이라도 입고일과 소비기한이
다르면 별도 로트다. 상품의 총재고는 그 상품 로트들의 잔량 합계다.

```
product_option <- stock_lot <- stock_allocation -> order_item
                     |
               stock_movement (모든 변동의 원장)
```

## 로트 입고

```
POST /v1/admin/products/{productId}/options/{optionId}/lots
```

```json
{
  "requestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "receivedDate": "2026-08-17",
  "expiryDate": "2026-08-31",
  "initialQty": 200
}
```

| 필드 | 필수 | 제약 |
|---|---|---|
| `requestId` | O | 클라이언트가 생성하는 요청 식별자. 100자 이하 |
| `receivedDate` | | 기본 오늘 |
| `expiryDate` | O | **`receivedDate` 이상이어야 한다** |
| `initialQty` | O | 1 이상 100,000 이하 |

입고하면 `available_qty` 가 `initialQty` 로 시작하고 **`INBOUND` 변동 이력이 함께 기록된다.**
**같은 `requestId`로 재시도하면 새로 입고하지 않고 최초 입고 결과를 그대로 돌려준다** (`API-5-07`, `AIP-155`).

| 오류 | 코드 | 언제 |
|---|---|---|
| `422` | `STOCK-001` | 소비기한이 입고일보다 이르다 |
| `404` | `STOCK-002` | 없거나 삭제된 상품 |
| `409` | `STOCK-007` | 이미 다른 옵션에 사용된 요청 식별자 |

## 로트별 조회

```
GET /v1/admin/products/{productId}/lots?availableOnly=true
```

**소비기한 오름차순이다.** FEFO 로 나가는 순서와 같다.

```json
{
  "lots": [
    {
      "stockLotId": 77,
      "productOptionId": 31,
      "receivedDate": "2026-08-17",
      "expiryDate": "2026-08-31",
      "initialQty": 200,
      "availableQty": 143,
      "status": "AVAILABLE"
    }
  ]
}
```

| 상태 | 뜻 |
|---|---|
| `AVAILABLE` | 판매 가능 |
| `SOLD_OUT` | 소진 |
| `DISPOSED` | 폐기 |
| `EXPIRED` | 소비기한 경과 |

**`AVAILABLE` 이 아니면 `availableQty` 가 0이어야 한다.** DB 가 CHECK 로 강제한다.
그렇지 않으면 FEFO 조회가 없는 재고를 집는다.

## 변동 이력

```
GET /v1/admin/products/{productId}/stock-movements?from=&to=&movementType=&lotId=
```

**발생 시각 내림차순이다.** 재고 정합성 문제가 생겼을 때 원인을 추적하는 경로다.

```json
{
  "movements": [
    {
      "stockMovementId": 9001,
      "stockLotId": 77,
      "movementType": "RESERVE",
      "quantity": 2,
      "qtyBefore": 145,
      "qtyAfter": 143,
      "orderId": 3012,
      "adminId": null,
      "createdAt": "2026-08-17T10:22:31.512"
    }
  ]
}
```

| 유형 | 뜻 | 수량 방향 |
|---|---|---|
| `INBOUND` | 신규 입고 | 증가 |
| `RESTOCK` | 반품 재입고 | 증가 |
| `RELEASE` | 예약 해제 | 증가 |
| `RESERVE` | 예약 | 감소 |
| `EXPIRE` | 만료 전환 | 감소 |
| `CONFIRM` | 차감 확정 | **변동 없음** |
| `DISPOSE` | 폐기 | 감소. 회수품 폐기는 변동 없음 |
| `ADJUST` | 수동 조정 | 양방향 |

**`CONFIRM` 은 앞뒤 수량이 같다.** 예약 시점에 이미 뺐기 때문이다.
DB 가 유형별 증감 방향을 CHECK 로 강제해서, 원장의 세 수치가 서로 어긋날 수 없다.

## 재고 조정

```
POST /v1/admin/lots/{lotId}:adjust
```

```json
{ "quantity": -5, "reason": "실사 차이" }
```

| 필드 | 필수 | 제약 |
|---|---|---|
| `quantity` | O | 0이 아닌 정수. 음수는 차감 |
| `reason` | O | **필수다.** 사유 없는 조정은 추적이 안 된다 |

| 오류 | 코드 | 언제 |
|---|---|---|
| `422` | `STOCK-003` | 조정 후 잔량이 음수가 된다 |
| `409` | `STOCK-004` | 판매 불가 로트는 조정할 수 없다 |

## 폐기

```
POST /v1/admin/lots/{lotId}:dispose
```

```json
{ "quantity": 12, "disposalReason": "EXPIRED", "reason": "소비기한 경과분" }
```

| `disposalReason` | 언제 |
|---|---|
| `EXPIRED` | 소비기한 경과 |
| `DAMAGED` | 손상 |
| `RETURNED` | **재입고하지 않은 회수품** |

**폐기는 사유와 처리자를 반드시 갖는다.** DB 가 CHECK 로 강제한다.

`RETURNED` 는 가용 수량을 바꾸지 않는다. 회수품은 애초에 판매 재고로 돌아온 적이 없기 때문이다.
그래서 원장에 `qtyBefore = qtyAfter` 로 남는다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `422` | `STOCK-005` | 폐기 수량이 로트 잔량을 넘는다 |

## 배치

### 만료 로트 처리

```
POST /v1/admin/lots:expire
```

소비기한이 지난 로트를 `EXPIRED` 로 바꾸고 폐기 등록으로 잇는다. 하루 한 번 도는 배치이며
이 경로는 수동 실행용이다.

**이미 폐기된 로트는 다시 처리하지 않는다.** 전환으로 상품의 가용 재고가 0이 되면 품절로 표시된다.

## 재고가 줄고 느는 시점

API 문서지만 이 순서를 알아야 응답을 읽을 수 있다.

```
주문 생성      RESERVE   available_qty 감소. FEFO 로 소비기한이 가까운 로트부터
결제 완료      CONFIRM   변동 없음. 예약을 확정으로 바꾼다
주문 취소      RELEASE   available_qty 복원
반품 승인      RESTOCK   잔여 소비기한이 기준 이상이면 원래 로트로 복원
              DISPOSE   기준 미만이면 폐기
```

**되돌릴 때 원래 로트를 찾는 경로가 있다.**

```
claim_item -> order_item -> stock_allocation -> stock_lot
```

가용 수량은 조건부 UPDATE 로 줄인다. 한 문장이라 읽고 쓰는 사이가 없다.

```sql
UPDATE stock_lot SET available_qty = available_qty - ?
WHERE stock_lot_id = ? AND available_qty >= ?;
```

**영향받은 행이 0이면 재고 부족이다.** 그 분기가 초과 판매를 막는 최종 방어선이라
실패 경로 테스트를 반드시 함께 작성한다.
