# 도메인 묶음

DDL 32개 테이블을 13개 도메인으로 나눈다.
**베이스 패키지의 직계 하위 패키지가 곧 도메인**이므로 이 목록이 그대로 패키지가 된다.
구조 규칙은 [domain-package-boundary-guideline.md](./domain-package-boundary-guideline.md) 에 있다.

| 도메인 | 테이블 | 층 |
|---|---|---|
| `member` | member, member_grade, address | L0 |
| `product` | product, product_option, product_image, category, supplier | L0 |
| `admin` | admin, audit_log | L0 |
| `stock` | stock_lot, stock_allocation, stock_movement | L1 |
| `coupon` | coupon, coupon_product_option, member_coupon, member_coupon_status_history | L1 |
| `cart` | cart, cart_item | L1 |
| `order` | orders, order_item, order_status_history | L2 |
| `payment` | payment, refund | L2 |
| `claim` | claim, claim_item, claim_attachment | L2 |
| `shipment` | shipment, shipment_photo | L2 |
| `review` | review | L2 |
| `qna` | qna | L2 |
| `statistics` | daily_sales | L2 |

32개 테이블이 빠짐없이 한 번씩 배정되어 있다.

## 호출 방향

```
L2  order, payment, claim, shipment, review, qna, statistics
      |
L1  stock, coupon, cart
      |
L0  member, product, admin
      |
    common, config (도메인 무지)
```

**아래로만 부른다. 같은 층끼리는 직접 부르지 않는다** (`DPB-5-01`).
같은 층이 필요하면 상위 도메인이 조립한다.

`ArchitectureTest` 가 빌드에서 강제한다. 네 경우로 확인했다.

| | 결과 |
|---|---|
| `cart` -> `coupon` (같은 층) | 막힌다 |
| `stock` -> `order` (위로) | 막힌다 |
| `order` -> `stock` (아래로) | 통과 |
| `stock` -> `product` (아래로) | 통과 |

## 왜 이렇게 나눴나

### `stock` 을 `product` 에서 뺐다

`stock_lot` 이 `product_option` 에 달려 있어 `product` 에 넣을 수도 있었다. 빼기로 한 이유는 둘이다.

**잠금이 있는 쓰기 경로를 읽기 위주 카탈로그와 한 서비스에 섞지 않는다.**
재고는 주문마다 `available_qty` 가 바뀌고 원장을 함께 쓴다. 카탈로그는 거의 안 바뀐다.

**따로 뒀다 합치는 것이 합쳤다 떼는 것보다 싸다.**

넣었을 때의 대가는 `product` 가 8개 테이블을 갖고 `ProductApi` 가 조회와 재고 연산을 겸하는 것이었다.

### `stock` 이 `order` 를 참조하는 것은 그대로 둔다

```
stock_allocation -> order_item
stock_movement   -> orders (NULL 허용)
```

**참조지 의존이 아니다.** 재고는 주문을 부르지 않고 주문 상태를 읽지도 않는다.
자기 원장에 변동의 원인을 적을 뿐이다.

`stock_allocation` 이 드는 것은 **어느 주문 라인이 어느 로트를 얼마나 쥐고 있는지의 현재 상태**다.
반품 때 되돌릴 대상을 한 행으로 읽는다.

```
claim_item -> order_item -> stock_allocation -> stock_lot
```

**이 조회 경로가 유일한 것은 아니다.** `stock_movement` 도 `order_id` 와 `stock_lot_id` 를 함께 들고
`order_item` 에 `UNIQUE (order_id, product_option_id)` 가 있어, 원장에서 옵션으로 좁혀도 로트를 찾을 수 있다.
`stock_allocation` 은 그 계산을 하지 않고 현재 상태를 한 행으로 읽게 해 주는 표다.

FK 를 빼면 고아 예약이 생긴다 (`DI-3-03`).

**코드에서는 엔티티 연관을 걸지 않는다.**

```java
private Long orderItemId;   // 이렇게 든다
// @ManyToOne private OrderItem orderItem;   이러면 순환이 생긴다
```

### 가용 수량은 컬럼으로 두고 조건부 UPDATE 로 줄인다

```sql
UPDATE stock_lot SET available_qty = available_qty - ?
WHERE stock_lot_id = ? AND available_qty >= ?;
```

예약 행만 쌓고 `initial_qty - SUM(qty)` 로 가용 수량을 구하는 방식도 있다.
삽입이라 행 경합이 없어 보이지만, **넣기 전에 합계를 확인해야 해서 읽고 쓰는 사이가 생긴다.**
그 구간은 `FOR UPDATE` 로 막아야 하고 잠금을 트랜잭션 내내 들게 되어 지금보다 길어진다.
CHECK 로는 못 막는다. 자기 행만 볼 수 있어서다 (`DI-3-06`).

조건부 UPDATE 는 한 문장이라 그 사이가 없고 잠금이 문장 동안만 잡힌다.
FEFO 목록 조회도 컬럼 하나를 읽으면 끝난다.

초과 판매를 나중에 취소해도 되는 도메인이면 삽입 전용이 낫다. **신선식품 재고는 그게 안 된다.**

### `payment` 와 `refund` 를 함께 둔다

`refund` 가 `claim` 과 `payment` 를 둘 다 참조해 어디에 둬도 가로지른다.
환불이 결제의 역연산이라 금액 정합성 검증이 한 도메인에서 끝나는 쪽을 골랐다.

### `statistics` 를 따로 뺐다

`daily_sales` 는 `product_option` 단위 집계다. 통계는 늘어나기 마련이라
나중에 빼는 것보다 처음부터 나눠 두는 편이 싸다.

### `admin` 을 L0 에 둔다

다섯 도메인이 `admin` 을 참조한다. 다만 **관리자 기능 자체는 각 도메인의 `Admin~` 클래스가 맡고**
(`DPB-4-06`), `admin` 도메인은 계정과 감사 로그만 갖는다.
이 구분이 흐려지면 `admin` 이 전 도메인을 아는 도메인이 된다.

## 만들 때 지킬 것

**서비스는 테스트와 함께 만든다.** `*.internal.service.*` 메서드 커버리지 100% 가 기준이라
빈 서비스 클래스를 먼저 만들면 그 순간부터 모든 병합이 막힌다.

**엔티티와 레포지토리는 커버리지 대상이 아니다.** 먼저 만들어도 빌드가 깨지지 않는다.

**`XxxApi` 는 다른 도메인이 실제로 호출할 때 만든다** (`DPB-1-03`). 미리 만들지 않는다.

**도메인을 더하거나 층을 옮기면 `ArchitectureTest` 의 `L1`, `L2` 배열도 함께 고친다.**
