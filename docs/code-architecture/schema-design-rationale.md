# 스키마 설계의 근거

이 문서는 `src/main/resources/db/migration/V1__init_schema.sql` 이 지금 모양이 된 이유를 남긴다.
DDL 주석이 **무엇을** 하는지를 적는다면 이 문서는 **왜 그렇게 했는지**와 **무엇을 포기했는지**를 적는다.

점검 항목을 뽑아내지 않는다. 판단 기준은 다른 가이드가 갖는다.

* 식별자: [identifier-strategy-guideline.md](./identifier-strategy-guideline.md)
* 엔티티 매핑: [entity-creation-guideline.md](./entity-creation-guideline.md), [jpa-rdb-guideline.md](./jpa-rdb-guideline.md)
* DB 제약 일반론: `fresh-market/.github` 의 `qa-data-integrity-guideline.md` 3장

---

## 1. 전체 구조

활성 32개 표를 8장으로 나눈다. 장 순서는 **참조가 앞에서 뒤로만 흐르도록** 정했다.

```
1. 회원 / 권한   member_grade, member, address, admin
2. 상품 / 재고   category, supplier, product, product_option, product_image, stock_lot
3. 쿠폰         coupon, coupon_product_option, member_coupon, member_coupon_status_history
4. 장바구니      cart, cart_item
5. 주문 / 결제   orders, order_item, stock_allocation, stock_movement,
                daily_sales, order_status_history, payment
6. 클레임        claim, claim_attachment, claim_item, refund, shipment, shipment_photo
7. 리뷰 / Q&A   review, qna
8. 공통         audit_log
```

**쿠폰이 주문보다 앞에 있는 것이 이 순서의 유일한 비직관 지점이다.**
`orders.member_coupon_id` 와 `order_item.member_coupon_id` 가 `member_coupon` 을 참조하기 때문이며,
뒤에 두면 외래 키를 `ALTER TABLE` 로 따로 걸어야 한다. 파일 안에 `ALTER TABLE` 이 하나도 없는 것이 이 배치의 결과다.

`notification` 은 주석 처리해 두었다. 발송 대상 리소스를 가리키는 참조가 없어 알림을 눌러 이동할 곳을 찾을 수 없고,
그 참조 형태는 알림 기능을 설계할 때 정해진다. 지금 만들면 쓰이지 않는 채로 형태가 굳는다.

---

## 2. 시각은 전부 `DATETIME(6)`

시각 컬럼 79개가 모두 마이크로초 정밀도다. 근거는 셋이고 첫째가 결정적이다.

**MySQL 은 정밀도가 낮은 컬럼에 넣을 때 버리지 않고 반올림한다.**

```
앱이 만든 값        2026-08-12 10:00:00.600
DATETIME 에 저장 -> 2026-08-12 10:00:01      아직 오지 않은 시각이 기록된다
```

절반의 확률로 미래 시각이 남는다. `ordered_at`, `paid_at`, `shipped_at` 처럼 실제 시각을 기록하는 컬럼에서 그대로 문제가 된다.
시각 순서를 보는 CHECK 가 여럿 있어(`chk_shipment_delivered_at`, `chk_claim_collect_at`)
앱이 검증한 값과 DB 값이 달라지면 예상 밖으로 걸린다.

**Hibernate 6 의 MySQL 방언은 `LocalDateTime` 을 `datetime(6)` 으로 잡는다.**
엔티티를 붙이고 `ddl-auto: validate` 를 켜는 순간 정밀도가 0이면 어긋난다.

같은 초 안의 순서를 가리지 못하는 문제도 있지만, `AUTO_INCREMENT` PK 가 타이브레이커라 실무상 해결된다.
`ORDER BY is_main DESC, sort_order ASC, product_image_id ASC` 처럼 정렬의 마지막 키로 PK 를 쓰는 곳이 그 예다.

`DATE` 다섯(`valid_from`, `valid_to`, `received_date`, `expiry_date`, `stat_date`)은 날짜만 필요한 값이라 그대로 둔다.

---

## 3. 조건부 유일성

"전체가 아니라 **일부 행 사이에서만** 유일" 이 여러 곳에 필요하다.
MySQL 에는 부분 인덱스(`CREATE UNIQUE INDEX ... WHERE`)가 없어 우회가 필요하다.

```sql
<이름>_key <타입> GENERATED ALWAYS AS (CASE WHEN <조건> THEN <유일해야 할 값> ELSE NULL END),
UNIQUE KEY uk_... (<이름>_key)
```

조건 밖 행은 `NULL` 이 되고, UNIQUE 가 `NULL` 을 여러 개 허용하므로 검사에서 빠진다.

### 남긴 여섯

| 표 | 컬럼 | 막는 것 |
|---|---|---|
| `category` | `parent_key` | 최상위끼리도 이름 중복 방지. `NULL` 을 0 으로 모은다 |
| `member_grade` | `is_default_key` | 기본 등급은 전체에서 최대 1개. 기본값은 `FALSE` 다. `TRUE` 였을 때는 등급을 둘째부터 못 넣었다 |
| `address` | `is_default_key` | 회원별 기본 배송지 1개 |
| `product_image` | `is_main_key` | 상품별 대표 이미지 1개 |
| `member` | `active_provider_key` | 활성 회원만 카카오 계정 유일 |
| `orders` | `active_coupon_key` | 살아 있는 주문만 쿠폰당 1건(취소, 전체 반품 제외) |
| `order_item` | `active_coupon_key` | 살아 있는 라인만 쿠폰당 1건(취소, 반품 제외) |

### `NULL` 이 반대로 작동한 자리

계산 컬럼 다섯은 **`NULL` 이면 UNIQUE 에서 빠진다**는 성질을 일부러 쓴다.
`category` 에서는 같은 성질이 사고로 작동했다.

```sql
parent_id BIGINT NULL,
UNIQUE KEY uk_category_parent_name (parent_id, name)
```

의도는 "같은 부모 밑에 같은 이름이 둘 있으면 안 된다" 인데, **최상위는 `parent_id` 가 `NULL` 이라 제약이 안 걸린다.**
하위 카테고리는 제대로 막히고 최상위만 뚫린다.

```
최상위 "수산물"       통과
최상위 "수산물" 중복   통과      <- 막혀야 한다
하위  "잎채소" 중복    거부
```

같은 도구를 반대로 써서 닫았다. `NULL` 을 실제 값으로 바꾸면 한 그룹으로 묶인다.

```sql
parent_key BIGINT GENERATED ALWAYS AS (COALESCE(parent_id, 0)),
UNIQUE KEY uk_category_parent_name (parent_key, name)
```

`category_id` 가 `AUTO_INCREMENT` 라 0 인 행이 없으므로 실제 부모와 충돌하지 않는다.

**다섯은 `NULL` 로 제약을 끄고, 하나는 `NULL` 을 없애 제약을 켠다.** 도구가 같고 방향만 다르다.

### 순환 참조는 DB 가 못 막는다

`category.parent_id` 가 자기 표를 참조하므로 **A 의 부모를 B 로, B 의 부모를 A 로 만들 수 있다.**
트리를 도는 코드가 무한 루프에 빠진다.

외래 키는 "그 행이 있는가" 만 보지 조상을 거슬러 올라가지 않는다. 재귀 검사라 CHECK 로도 표현할 수 없다.
정합성 검사가 재귀 CTE 로 잡는다.

### 걷어낸 둘

`product` 와 `review` 는 **재사용을 포기하고 평범한 UNIQUE 로 바꿨다.**

```sql
UNIQUE KEY uk_product_code (product_code)        -- 삭제한 상품코드를 다시 쓰지 않는다
UNIQUE KEY uk_review_orderitem (order_item_id)   -- 지운 리뷰의 주문 상품에 다시 쓰지 않는다
```

`product_code` 는 자동 생성 코드라 재사용할 이유가 없고, 리뷰는 수정으로 정정하면 된다.
**잃는 것이 거의 없어서 트릭을 쓸 값이 없었다.**

### 일부러 걸지 않은 유일성

`product_image` 의 `(product_id, sort_order)` 에는 UNIQUE 를 걸지 않는다.

**재정렬의 중간 상태가 항상 위반이 되기 때문이다.** `A:0 B:1 C:2` 에서 `C` 를 맨 앞으로 옮기려면
`C` 를 `0` 으로 바꿔야 하는데 그 순간 `A:0 C:0` 이 된다. 임시값이나 간격 저장(10, 20, 30)으로 우회할 수 있지만
순서 변경이 흔한 목록에서 그 복잡도를 지불할 이유가 없다.

대신 **결정적 순서는 타이브레이커로 얻는다.** 조회 정렬의 마지막 키가 `product_image_id` 인 이유가 그것이다.
값은 확정할 때 서버가 `MAX(sort_order) + 1` 로 정한다. 컬럼 기본값이 `0` 이라 앱이 안 넣으면 전부 `0` 이 되어
실질 정렬이 올린 순서로 떨어지고 컬럼이 무의미해진다.

### 검토했다가 안 쓴 방법

* **함수 인덱스** (`UNIQUE KEY uk_... ((CASE WHEN ...)))`, MySQL 8.0.13+)
  컬럼이 안 보이지만 MySQL 이 내부에서 숨은 생성 컬럼을 만드는 것이라 기법이 같고 이식성도 그대로다.
* **부모 쪽으로 참조를 뒤집기** (`product.main_image_id` 같은 모양)
  "최대 하나" 는 구조적으로 보장되지만 순환 외래 키가 생기고,
  **대표 이미지가 그 상품 것인지, 확정된 이미지인지를 DB 가 못 막게 된다.** 지금은 둘 다 막힌다.
* **별도 표로 분리** (`product_main_image(product_id PK, ...)`)
  표 3개와 교차 검증 3건이 늘어 계산 컬럼 3개를 없애는 값보다 비싸다.

---

## 4. 금액

### 항등식을 DB 가 강제한다

```sql
CONSTRAINT chk_order_total    CHECK (total_amount = product_amount - discount_amount + shipping_fee)
CONSTRAINT chk_order_discount_cap CHECK (discount_amount <= product_amount)
```

각 항목이 0 이상인지만 보면 `total_amount` 가 아무 값이나 될 수 있다. 한 행 안의 값들이라 CHECK 로 닫힌다.
할인 상한을 상품금액으로 잡은 것은 **배송비를 깎는 쿠폰을 두지 않기 때문**이다. 배송비 할인이 생기면 이 식이 바뀐다.

### 라인별 배분을 저장한다

`order_item.discount_amount` 는 **부분 반품 환불액의 근거**다.

```
환불액 = (unit_price * qty - discount_amount) * 반품수량 / qty
```

이 컬럼이 없으면 어느 라인이 할인받았는지 몰라 전 라인에 안분할 수밖에 없고, 특정 라인만 할인한 경우 금액이 틀어진다.

### 장바구니 쿠폰은 잔액 비례로 나눈다

```
1) 상품 쿠폰을 각 라인에 적용해 coupon_discount 를 확정한다
2) 라인 잔액 = unit_price * qty - coupon_discount
3) 장바구니 쿠폰을 라인 잔액 비례로 안분한다
4) discount_amount = coupon_discount + 안분액
5) 잔차는 잔액이 가장 큰 라인에 더한다
```

**정가 비례가 아닌 이유는 취향이 아니라 제약을 깨기 때문이다.**

```
10,000원 라인에 상품 쿠폰 9,500원이 붙은 경우
  정가 비례 -> 1,667원을 더 배정해 할인이 11,167원. chk_orderitem_discount 위반
  잔액 비례 -> 잔액 500원에 비례해 122원만 배정. 넘칠 수 없다
```

이미 깎인 만큼은 더 깎을 수 없다는 것을 비율에 반영하면 넘침이 구조적으로 사라진다.

### 0원 주문은 결제 행을 만들지 않는다

할인이 상품금액과 배송비를 다 덮으면 `total_amount = 0` 이 되고, PG 를 타지 않으므로 `payment` 행이 없다.
`refund` 도 없다. 돌려줄 돈이 없다는 것이 사실이기 때문이다.

**대가는 조회가 갈린다는 것이다.** 결제 여부를 묻는 조회에 `INNER JOIN payment` 를 쓰면 그런 주문이 결과에서 사라진다.

### 결제액과 환불 총액을 DB 가 쥔다

검토 과정에서 돈에 관한 두 값이 어느 제약에도 걸려 있지 않은 것을 발견했다.

```
payment.amount 가 orders.total_amount 와 달라도 아무도 보지 않는다
한 주문에 클레임이 여럿이면 환불 총액이 결제액을 넘을 수 있다
```

둘 다 표를 넘나드는 값이라 CHECK 범위 밖인데, **성질이 달라서 답도 다르다.**

앞의 것은 값 하나 대 값 하나라 **복합 외래 키로 옮겼다.**

```sql
orders:  UNIQUE KEY uk_order_id_total (order_id, total_amount)
payment: FOREIGN KEY (order_id, amount) REFERENCES orders (order_id, total_amount)
```

`amount` 를 따로 저장하지만 그 값이 외래 키로 강제되므로 어긋날 수 없다. `claim_item` 이 `order_id` 를 복제한 것과 같은 기법이다.
덤으로 **결제 행이 있는 동안 `orders.total_amount` 수정이 막힌다.** 10장이 불변으로 두고 싶다고 적어 둔 컬럼이라 방향이 같다.

뒤의 것은 **자식 행의 합**이라 외래 키로 표현할 수 없다. 부모에 소진 카운터를 둔다.

```sql
payment: refunded_amount INT NOT NULL DEFAULT 0,
         CHECK (refunded_amount >= 0 AND refunded_amount <= amount)
```

```sql
UPDATE payment SET refunded_amount = refunded_amount + :amt
 WHERE payment_id = :id AND refunded_amount + :amt <= amount;
-- affected rows 0 이면 초과 환불이다
```

조건부 UPDATE 가 동시성을, CHECK 가 최종 상한을 맡는다. `coupon.issued_quantity` 와 같은 구조다.

`refund` 에 `order_id` 를 복제해 결제에 직접 닿게 했다.

```sql
FOREIGN KEY (claim_id, order_id) REFERENCES claim (claim_id, order_id)
FOREIGN KEY (order_id)           REFERENCES payment (order_id)
```

**0원 주문은 `payment` 행이 없으므로 환불 행을 만들 수 없다.** 이걸 넣지 않으면 상한을 쥔 행이 없는 자리에서 환불이 무제한이 된다.

라인 단위로 환불을 쪼개 각 라인 금액을 넘지 않게 하는 안(`refund_item`)도 있었다. 강제는 더 촘촘해지지만
표가 하나 늘고 `claim_item` 과 거의 같은 모양이 된다. **부분 수량 반품을 하지 않기로 한 것과 같은 이유로 접었다.**

### 결제 콜백의 방어는 둘로 나뉜다

`payment.pg_tid` 는 결제 요청 전에 발급되지 않아 `NULL` 이다. UNIQUE 와 `NULL` 여러 개 허용이 맞물려
발급 전 행이 몇이든 충돌하지 않고, 값이 붙은 것끼리만 유일해진다.

**UNIQUE 가 막는 것과 중복 콜백을 막는 것이 다르다.**

```
UNIQUE (pg_tid)   한 PG 거래가 두 주문에 붙는 것
                    남의 거래번호를 자기 주문에 실어 보내는 경우

조건부 UPDATE     같은 콜백이 두 번 오는 것
                    UPDATE ... WHERE payment_id = ? AND status = 'PENDING'
                    affected rows 0 이면 이미 처리됐다 (DI-2-01)
```

콜백을 두 번 받는 것은 같은 행을 두 번 `UPDATE` 하는 일이라 UNIQUE 가 막지 못한다. 둘 다 필요하다.

`chk_payment_pg_tid` 가 `status='PAID'` 인 행에 거래번호를 강제한다. 모든 결제 수단이 PG 를 타므로 예외가 없다.

### 배송은 주문당 1건이다

`shipment` 에 `UNIQUE (order_id)` 를 건다. **분할 배송을 허용하면 어느 라인이 어느 송장에 실렸는지 담을 곳이 필요해진다.**

```
주문   감귤 3kg, 한우 500g
배송1  송장 A
배송2  송장 B
       -> 감귤이 어느 쪽인지 표현할 방법이 없다
```

`shipment` 가 `order_id` 만 갖고 있으면 고객 문의에 답할 수 없고, `shipment.status='DELIVERED'` 를 라인으로 내릴 수도 없다.
표현하려면 `order_item.shipment_id` 나 `shipment_item` 이 필요한데, 주문당 1건이면 그 표가 필요 없다.

필요해지면 `UNIQUE` 를 떼고 라인 연결을 더하는 마이그레이션으로 넘어간다.

### 무통장입금을 두지 않는다

결제 수단은 `CARD` 와 `EASY_PAY` 둘이다. **신선식품이라 입금까지 최대 24시간 재고를 붙잡는 것을 감당할 수 없다.**
그동안 소비기한이 줄고, 그 로트를 살 수 있었던 다른 손님을 막는다.

즉시 결제만 받으므로 주문에서 결제까지의 구간이 짧고, `stock_allocation.RESERVED` 의 해제 유예도 그만큼 짧게 잡을 수 있다.
이 결정으로 `payment.payment_due_dt` 컬럼이 사라졌다.

---

## 5. 쿠폰

### `coupon` 은 틀이고 `member_coupon` 이 실제 쿠폰이다

`member_coupon` 은 발급 조건을 복사하지 않는다. **쿠폰 정책은 한 번 만들면 바뀌지 않기 때문이다.**

```
참조로 둔다   coupon_name  discount_type  discount_value
              max_discount_amount  min_order_amount  valid_from  valid_to

행에 남긴다   scope        복합 외래 키에 쓰인다 (아래)
              issue_limit  한정 수량을 강제하는 장치다 (아래)
```

**원래는 복사했고, 근거는 `coupon` 이 살아 있는 표라 관리자가 고칠 수 있다는 것이었다.**

```
5월  coupon 42: '신규가입 5,000원', 5000   -> 주문. coupon_discount = 5000 저장
8월  관리자가 그 쿠폰을 재활용해 '여름 특가 8,000원', 8000 으로 수정
     -> 5월 주문을 열면 "여름 특가 8,000원 적용, -5,000원"
```

**정책을 불변으로 두면 이 시나리오가 성립하지 않는다.** 쿠폰을 고쳐 재활용하는 대신 새 `coupon` 행을 만든다.
`order_item` 의 `name_snapshot` 과는 성격이 갈린다. 상품명과 가격은 실제로 바뀌지만 쿠폰 정책은 안 바뀐다.

**얻는 것은 행 크기다.** `coupon_name VARCHAR(100)` 하나만 빼도 행이 백 바이트 가까이 줄고,
그만큼 페이지당 행 수가 늘어 쓰기와 버퍼 풀이 가벼워진다. 발급 이력이 300만 건 규모라 누적 효과가 크고,
선착순 발급 경로가 `INSERT` 하나뿐이라 **행 크기가 곧 쓰기 비용이다**(`coupon.md` 3장).

**대가는 전제 하나다.** 정책 수정 기능이 생기면 이미 발급된 쿠폰의 조건까지 소급해 바뀐다.
그때는 위 일곱 컬럼을 되돌려 다시 복사한다. **컬럼을 다시 더하는 방향이라 되돌리기가 어렵지 않다.**

**쿠폰함 조회에는 `coupon` 조인이 붙는다.** 발급이 아니라 조회 경로의 비용이다.

### 대상 옵션은 필수이고 복사하지 않는다

`ITEM` 쿠폰은 대상 옵션을 하나 이상 반드시 갖는다. 행이 없는 것을 "대상 제한 없음" 으로 해석하지 않는다.
그 해석은 관리자가 대상을 실수로 지웠을 때 전 상품 할인으로 바뀌고, 그 사고가 조용하다.
대상이 필수라야 `order_item` 이 복합 외래 키로 대상 여부를 강제할 수 있기도 하다.

**`scope` 만 행에 남는다.** 나머지 일곱과 달리 이것은 스냅샷이 아니라 **복합 외래 키의 한 칸**이라,
`coupon.scope` 와 같기를 강제하는 역할을 한다.

```sql
FOREIGN KEY (coupon_id, scope) REFERENCES coupon (coupon_id, scope)
```

이 한 칸이 비어 있을 때 사슬이 끊겨 있었다.

```
coupon.scope  --FK-->  coupon_product_option.scope    이어짐
coupon.scope    끊김   member_coupon.scope            <- 여기
member_coupon.scope --FK--> orders.coupon_scope       이어짐
```

**발급할 때 앱이 `ITEM` 쿠폰의 `scope` 에 `'ORDER'` 를 잘못 쓰면 그 쿠폰이 장바구니 쿠폰 행세를 하며 주문에 붙었다.**
`orders` 의 외래 키 둘과 CHECK 가 전부 통과한다. `orders` 는 `member_coupon.scope` 를 믿는데 그 값이 검증되지 않았기 때문이다.
`chk_mc_scope` 는 값이 `ORDER` 냐 `ITEM` 이냐만 볼 뿐 어느 쿠폰에서 나왔는지는 못 본다.

`coupon` 에 `uk_coupon_id_scope` 가 이미 있어서 참조 대상을 새로 만들 필요는 없었다.
부수 효과로 **발급분이 있는 쿠폰은 `scope` 를 바꿀 수 없게 된다.** 이미 나간 쿠폰의 성격을 바꾸는 것은
정상 동작이 아니라 막히는 편이 맞고, 대상 옵션이 있는 `ITEM` 쿠폰은 `fk_cpo_coupon` 때문에 지금도 못 바꾼다.

`member_coupon.scope` 를 지우고 유도하는 길도 봤다. 그러면 `orders` 가 범위를 확인할 방법이 없어진다.
`orders` 는 `coupon_id` 를 안 갖고 `member_coupon_id` 만 갖기 때문에, 확인하려면 `orders` 에 `coupon_id` 를 복제하고
외래 키를 둘로 늘려야 한다. **컬럼 수는 그대로인데 사슬만 길어져서** 지금 자리에 두는 쪽을 택했다.

`coupon_product_option` 은 참조 그대로 두고 사용 시점의 현재 목록을 본다.
**금액과 조건은 발급 시점에 고정되어야 하지만, "어디에 쓸 수 있나" 는 운영이 조정하는 것**이라 성질이 다르다.
임박 재고가 팔리면 대상에서 빼는 식으로 조정할 수 있어야 한다.

**대상에서 뺄 때 행을 지우지 않고 `is_active` 를 내린다.**
`order_item` 이 이 표를 복합 외래 키로 참조하기 때문이다. 지우려 하면 그 쿠폰으로 그 옵션을 산 라인이
하나라도 있을 때 `RESTRICT` 에 걸리는데, **빼고 싶어지는 이유가 바로 그 옵션이 팔렸다는 것**이라
필요한 순간에 정확히 막힌다. 살아 있는 운영 데이터를 과거 기록의 참조 대상으로 삼은 대가다.

발급 시점에 목록을 복사하는 안(`member_coupon_target`)도 검토했다. 강제는 완전해지지만
**이미 발급된 쿠폰에 조정이 반영되지 않아** 애초의 요구를 못 채운다. 대상을 빼는 목적이 그것이었다.

### 같은 이름의 is_active 가 둘이다

이름이 같아 헷갈리기 쉬운데 **켜고 끄는 대상이 다르다.**

| | 무엇의 스위치인가 | 누가 언제 |
|---|---|---|
| `coupon.is_active` | 쿠폰 자체의 발급 | 관리자가 이벤트를 열고 닫을 때 |
| `coupon_product_option.is_active` | 그 쿠폰을 쓸 수 있는 옵션 하나 | 운영이 대상 목록을 조정할 때 |

**판정 시점도 갈린다.** 앞의 것은 발급 시점에, 뒤의 것은 쿠폰을 주문에 적용하는 시점에 본다.
선착순 발급 로직은 `coupon.is_active` 만 보면 되고 대상 옵션을 조회할 이유가 없다.

기본값이 반대인 것도 그 성질에서 나온다. 쿠폰은 초안(`FALSE`)으로 태어나 사람이 켜지만,
대상 옵션은 등록하는 행위 자체가 대상으로 삼겠다는 뜻이라 `TRUE` 로 태어난다.

### 선착순은 별도 표를 두지 않는다

`coupon.total_quantity` 가 `NULL` 이면 일반 쿠폰, 값이 있으면 선착순이다.

캠페인을 별도 표로 두었다가 걷어냈다. 그때는 `member_coupon` 이
**유도 가능한 참조를 셋**(대상 옵션 -> 캠페인 -> 쿠폰) 들고 있었고,
셋의 정합을 아무도 검증하지 않았으며, 발급 카운터가 둘로 나뉘어 합이 맞는지도 볼 수 없었다.

`is_active` 만 두고 `SCHEDULED/OPEN/CLOSED` 상태는 뺐다.
**소진 여부는 `issued_quantity` 로 유도되므로** 저장하면 어긋날 자리만 생긴다.
사람이 켜고 끄는 것만 이 컬럼으로 표현하고, 기본값은 `FALSE` 라 쿠폰은 초안으로 태어난다(9장).

### 한정 수량은 순번으로 강제한다

`coupon.issued_quantity` 는 카운터고 실제 발급 수는 `member_coupon` 행 수다. **둘은 어긋날 수 있다.**
카운터가 실제보다 작으면 한정 수량을 넘겨 발급된다. 부모 카운터 대 자식 행 수라 `DI-3-06` 형태이고,
CHECK 는 자기 행만 보므로 "행 수를 세어 비교하라" 를 제약으로 쓸 수 없다.

**세는 대신 번호를 매겼다.** 발급분이 몇 번째인지를 자기 행에 들고 있으면 자기 행만 보고도 한도를 알 수 있다.

```sql
issue_limit INT NULL,   -- 발급 시점 coupon.total_quantity 복사
issue_seq   INT NULL,   -- 발급 순번. 무제한 쿠폰은 NULL

UNIQUE KEY uk_mc_coupon_seq (coupon_id, issue_seq),
CHECK ((issue_limit IS NULL     AND issue_seq IS NULL)
    OR (issue_limit IS NOT NULL AND issue_seq IS NOT NULL AND issue_seq >= 1 AND issue_seq <= issue_limit))
```

순번은 카운터를 올린 값을 그대로 쓴다. 조건부 UPDATE 가 잡은 행 잠금이 커밋까지 유지되므로 같은 번호가 둘에게 가지 않는다.

```sql
UPDATE coupon SET issued_quantity = issued_quantity + 1
 WHERE coupon_id = ? AND is_active
   AND (total_quantity IS NULL OR issued_quantity < total_quantity);
-- affected rows 0 이면 소진이거나 꺼진 쿠폰이다
-- 갱신된 issued_quantity 가 그 발급분의 issue_seq 가 된다
```

**카운터가 어긋나도 초과 발급이 안 된다.**

| 어긋난 방향 | 무슨 일이 나나 | 무엇이 막나 |
|---|---|---|
| 카운터가 작다 | 이미 쓴 번호를 다시 발급한다 | `uk_mc_coupon_seq` |
| 카운터가 크다 | 번호가 한도를 넘는다 | `chk_mc_issue_seq` |

서로 다른 번호가 `1..issue_limit` 안에만 존재하므로 **행 수는 한도를 넘을 수 없다.**
카운터의 정확성은 여전히 앱 몫이지만, 그 오차가 한정 수량을 깨뜨리지는 못한다.

`issue_limit` 은 다른 조건들과 이유가 다르다. **표시용 스냅샷이 아니라 이 제약을 성립시키는 장치다.**
CHECK 은 다른 테이블을 못 보므로 `coupon.total_quantity` 를 참조로 두면 위 CHECK 자체를 걸 수 없고,
한정 수량 강제가 DB 를 떠나 앱으로 넘어간다. **정책을 불변으로 두더라도 이 컬럼은 남는다.**

무제한 쿠폰은 둘 다 `NULL` 이다. MySQL UNIQUE 가 `NULL` 을 중복으로 보지 않아 **번호를 다투지 않는다.**
`NULL` 을 비워 두는 것으로 제약을 끄는 방식은 3장의 조건부 유일성과 같은 수법이다.

### 쿠폰을 한 표로 모으지 않는다

`order_coupon` 같은 표에 두 층을 모으는 안을 검토했다가 걷어냈다.
**지금은 층 구분이 공짜인데, 한 표로 모으면 그것이 조건이 되기 때문이다.**

```
컬럼으로 두면   orders.member_coupon_id      컬럼이 하나라 주문당 1장이 구조적으로 보장된다
                order_item.member_coupon_id  컬럼이 하나라 라인당 1장이 구조적으로 보장된다

한 표로 모으면   order_item_id 의 NULL 여부가 층을 가른다
                주문당 1장   -> order_item_id IS NULL AND status='APPLIED'  조건부 유일성
                라인당 1장   -> status='APPLIED'                            조건부 유일성
```

계산 컬럼이 둘에서 셋으로 늘고, 표와 조인이 하나씩 는다.
취소 시 행을 지우면 계산 컬럼은 줄지만 이력을 잃는다.

한 표가 주는 것은 **할인 종류가 늘어도 표를 안 만드는 것**과 **한 주문에 쿠폰 여러 장**인데,
할인 원천이 쿠폰 둘로 고정이고 주문당 1장으로 정해서 둘 다 값을 못 낸다.

**전제가 바뀌면 답도 바뀐다.** 포인트가 돌아오거나 쿠폰 중복 사용을 허용하기로 하면
그때는 `order_discount` 로 넓히는 쪽이 자연스럽다.

### 취소를 상태로 남긴다

발급분 상태에 `CANCELED` 를 두어 넷이 됐다.

```
ISSUED     발급됨
USED       사용됨
EXPIRED    만료됨
CANCELED   주문 취소로 사용이 철회됨
```

`used_at` 은 함께 비운다. `chk_mc_used_at` 이 `status <> 'USED'` 에 `used_at IS NULL` 을 요구하므로
`CANCELED` 로 바꾸면서 시각을 남겨 두면 제약에 걸린다. **사용이 철회됐으니 사용 시각도 남지 않는 것이 맞다.**
언제 썼었는지는 `member_coupon_status_history` 의 `USED` 전이 행이 갖는다.

### `CANCELED` 는 조회 시점에 해소한다

`CANCELED` 는 **머무는 상태가 아니라 거쳐 가는 상태**다. 쿠폰함을 조회할 때 앱이 정리한다.

```
CANCELED 이고 유효기간이 지났으면   ->  EXPIRED
CANCELED 이고 아직 유효하면        ->  ISSUED
```

취소 시점이 아니라 조회 시점에 판정하는 이유는 **유효기간 만료 여부가 시간이 지나면 바뀌기 때문이다.**
취소할 때 `ISSUED` 로 되돌려 놓아도 그 뒤에 기간이 지나면 다시 `EXPIRED` 로 바꿔야 하고, 그건 만료 배치가 한다.
`CANCELED` 로 두면 **다음에 사용자가 볼 때 그 시점 기준으로 한 번에 판정된다.**

대가가 둘 있다.

**읽기가 쓰기를 한다.** 쿠폰함 목록 조회가 `CANCELED` 행마다 `UPDATE` 를 낸다.
동시 조회가 같은 행을 건드리므로 조건부 `UPDATE` 로 처리하고 `affected rows` 가 0이면 남이 이미 했다고 본다.

**조회 전에는 상태가 옛 값이다.** 쿠폰함을 안 열고 바로 주문서로 가면 그 쿠폰은 아직 `CANCELED` 다.
주문서의 쿠폰 목록 조회도 같은 해소 경로를 타야 한다. **조회 지점이 여럿이면 전부 같은 규칙을 적용해야 한다.**

`orders` 쪽 재사용 차단과는 별개다. 주문이 취소되면 `active_coupon_key` 가 `NULL` 이 되어
UNIQUE 는 이미 풀려 있다. `member_coupon.status` 가 늦게 해소돼도 **재사용 자체는 막히지 않고 화면 표시만 옛 값이다.**

### 상태 전이를 이력으로 남긴다

`member_coupon.status` 는 현재 상태만 갖고 `updated_at` 은 마지막 전이 시각만 가리킨다.
**"언제 왜 이렇게 됐나" 를 답할 수 없어** `member_coupon_status_history` 를 둔다.
`orders` 가 `order_status_history` 로 푸는 것과 같은 형태다.

`reason` 컬럼이 이 표의 핵심이다. 상태가 `ISSUED / USED / EXPIRED` 셋뿐이라
**만료가 유효기간 도래인지 어뷰징 발급 취소인지 상태값만으로는 갈리지 않는다.**
`changed_by` 가 `NULL` 이면 배치나 사용자 동작으로 자동 전이한 것이고, 값이 있으면 관리자가 손댄 것이다.

선착순 쿠폰은 소진과 취소를 두고 분쟁이 생기는 자리라 근거가 남아야 한다.
주문 취소로 쿠폰을 되살리는 전이(`USED -> ISSUED`)도 행으로 남는다.

### 층은 발행 시점에 정해지고 양쪽에서 강제한다

`coupon.scope` 가 `ORDER`(장바구니 쿠폰) 또는 `ITEM`(상품 쿠폰) 이다.
**잘못된 층에 붙이는 것을 두 방향 모두 DB 가 막는다.**

```
ORDER 쿠폰을 라인에    fk_orderitem_coupon_target 이 coupon_product_option 을 찾는데
                       ORDER 쿠폰은 그 표에 행을 가질 수 없어 참조할 대상이 없다

ITEM 쿠폰을 주문에     orders.coupon_scope 를 복제하고
                       CHECK (coupon_scope = 'ORDER') + FK (member_coupon_id, coupon_scope)
                       -> member_coupon (member_coupon_id, scope)
```

한쪽만 막으면 반대 방향이 조용히 통과한다. 처음에는 라인 쪽만 막혀 있었다.
주문당 1장과 라인당 1장은 **컬럼이 하나라는 사실만으로** 보장되고,
한 쿠폰이 두 곳에 쓰이는 것은 각 `UNIQUE (member_coupon_id)` 가 막는다.
취소하면 `NULL` 로 비워 되돌린다.

### 취소해도 참조를 비우지 않는다

주문이나 라인을 취소해도 `member_coupon_id` 를 그대로 둔다. **참조는 "이 주문이 그 쿠폰을 썼다" 는 사실이고,
취소해도 그 사실은 변하지 않는다.** 변한 것은 주문의 상태이고 `orders.status='CANCELED'` 가 이미 표현한다.

비우면 참조가 관계와 상태를 겸하게 되어, 상태를 바꾸려고 관계를 지우게 된다. 그 순간
`chk_order_coupon` 이 `coupon_discount` 도 0으로 만들라고 요구하고 **할인 5,000원의 근거가 사라진다.**

대신 계산 컬럼이 상태를 유일성 검사가 볼 수 있는 형태로 바꾼다.

```sql
active_coupon_key BIGINT GENERATED ALWAYS AS
    (CASE WHEN status NOT IN ('CANCELED','RETURNED') THEN member_coupon_id ELSE NULL END),
UNIQUE KEY uk_order_active_coupon (active_coupon_key)
```

```
member_coupon_id  = 42         관계. 영원히 안 바뀐다
status            = 'CANCELED' 상태
active_coupon_key = NULL       파생. 유일성 검사에서 빠진다
```

### 해제 조건은 주문과 라인이 같다

둘 다 `CANCELED` 와 `RETURNED` 에서 쿠폰을 풀고 교환에서는 유지한다. 상품이 유지되면 쿠폰도 유지한다는 규칙이다.

`orders` 쪽이 처음에는 `status <> 'CANCELED'` 였다. **전액 환불받고 쿠폰만 못 돌려받는 자리**였다.
라인은 `RETURNED` 에서 풀리는데 주문은 안 풀렸다.

고치기 전에 확인해야 했던 것이 있다. **부분 반품에서도 헤더가 `RETURNED` 가 된다면 이 수정은 위험하다.**

```
라인 3개 중 1개만 반품 -> 헤더가 RETURNED -> 쿠폰 해제
남은 라인 2개는 여전히 그 쿠폰 할인을 받은 상태인데 같은 쿠폰을 새 주문에 또 쓴다
```

정책이 이 전제를 만족한다.

```
취소       라인 단위로 일어나지 않는다. 주문을 취소하면 라인 전부가 CANCELED 가 된다
부분 반품   헤더를 바꾸지 않고 라인만 바꾼다. 헤더의 RETURNED 는 전체 반품만 뜻한다
```

**이 전제는 DB 가 강제하지 못한다.** "헤더가 `RETURNED` 면 모든 라인이 `RETURNED` 인가" 는 다른 행을 봐야 해서
CHECK 범위 밖이다(9장 행의 부재와 같은 부류). 그래서 `orders.status` 주석에 명시하고 정합성 검사를 하나 뒀다.

```sql
SELECT o.order_id FROM orders o
 WHERE o.status IN ('CANCELED','RETURNED')
   AND EXISTS (SELECT 1 FROM order_item i
                WHERE i.order_id = o.order_id AND i.item_status NOT IN ('CANCELED','RETURNED'));
```

**이 검사가 계산 컬럼의 안전판이다.** 전제가 깨지면 여기서 드러난다.

**`NULL` 이 되는 것은 계산 컬럼이지 외래 키가 아니다.** 취소나 전체 반품에서 `status` 만 바꾸면 그 쿠폰이 자동으로 풀려
다른 주문에서 다시 쓸 수 있다. `member_coupon.status` 를 `ISSUED` 로 되돌리는 것은 앱이 한다.

**두 계산 컬럼이 서로 다른 컬럼을 본다.** `orders` 는 `status` 를, `order_item` 은 `item_status` 를 본다.
계산 컬럼은 같은 행의 값만 참조할 수 있어서 라인이 주문 상태를 볼 방법이 없다.

```
UPDATE orders SET status = 'CANCELED'
  -> 장바구니 쿠폰은 풀린다
  -> order_item.item_status 는 그대로다. 상품 쿠폰은 계속 잠긴다
```

**주문을 취소할 때 라인도 함께 `CANCELED` 로 바꿔야 한다.**

```sql
UPDATE orders     SET status = 'CANCELED' WHERE order_id = ?;
UPDATE order_item SET item_status = 'CANCELED' WHERE order_id = ? AND item_status = 'ORDERED';
```

이건 쿠폰 때문이 아니라 **어차피 해야 하는 일**이다. 주문이 취소됐는데 라인이 `ORDERED` 로 남으면
재고 해제, 클레임 가능 여부, 통계가 전부 틀린 값을 본다. 쿠폰 잠금은 그 누락이 드러나는 자리 하나일 뿐이다.

`orders.status` 를 라인에 복제하고 `ON UPDATE CASCADE` 로 따라가게 만들면 앱이 잊을 자리가 없어지지만,
**상태가 12단계를 지나는 동안 전이마다 라인 수만큼 쓰기가 발생한다.** 라인 20개짜리 주문이면 전이 하나에 20행이다.
그 비용을 치르기보다 앱이 두 UPDATE 를 함께 하는 쪽을 택했다.

실패해도 조용하지 않다는 점도 고려했다. 쿠폰이 잠기면 사용자가 알아채고, 돈이 새는 방향이 아니다.

이 방식만 **앱이 무언가를 잊어도 막힌다.** `UNIQUE` 를 떼고 `member_coupon.status` 조건부 UPDATE 에 맡기거나
잠금 표를 두는 방법은, 앱이 그 UPDATE 나 INSERT 를 빼먹으면 아무 오류 없이 쿠폰이 두 번 먹힌다.

---

## 6. 재고

### `available_qty` 는 판매 가능 수량이다

```
INBOUND   +qty    신규 입고
RESTOCK   +qty    반품 재입고
RESERVE   -qty    주문 시점에 남이 못 잡게 뺀다
CONFIRM    0      이미 RESERVE 에서 뺐다
RELEASE   +qty    결제 취소/만료로 되돌린다
DISPOSE   -qty
EXPIRE    -qty
```

**`CONFIRM` 이 값을 바꾸지 않는 것이 이 표의 가장 오해하기 쉬운 지점이다.**
`stock_allocation.status` 가 `CONFIRMED=차감 확정(결제)` 이라고만 되어 있으면 결제에서 또 빼게 되고 재고가 두 배로 줄어든다.
세 표의 주석이 모두 이 사실을 말하도록 맞춰 두었다.

그래서 `stock_movement` 에 `CONFIRM` 행은 `qty_before = qty_after` 다.
재고를 옮기지 않고 예약이 확정으로 넘어간 사실만 남긴다. `movement_type` 별로 `qty_after` 를 검사하려면
이전 행을 봐야 해서 CHECK 로는 막을 수 없다.

### 할당은 현재 상태이고 이력이 아니다

`stock_allocation` 은 **지금 이 주문 상품이 어느 로트를 얼마나 잡고 있나**를 담는다.
언제 잡았고 언제 풀었는지는 `stock_movement` 가 `RESERVE`, `CONFIRM`, `RELEASE` 로 이미 갖고 있다.

처음에는 이 표의 주석이 "예약/차감 이력" 이었고, 그래서 유일성을 걸 수 없었다.
**두 표가 같은 것을 이력으로 담으려 한 것이 원인이었다.**

```sql
UNIQUE KEY uk_alloc_orderitem_lot (order_item_id, stock_lot_id)
```

이게 없으면 재시도로 같은 예약이 두 번 들어가 `available_qty` 가 이중 차감된다.
로트 재고가 4개 빠졌는데 주문은 2개인 상태가 조용히 만들어진다.

**재예약에 새 행이 필요 없다.** 같은 행의 `status` 를 `RELEASED` 에서 `RESERVED` 로 되돌리면 되고,
그 전이는 `stock_movement` 에 남는다. 조합당 행이 하나뿐이라 조건부 유일성도 필요 없다.

`stock_lot.available_qty` 와 `stock_movement` 의 관계와 같은 구조다. 잔액은 현재 상태, 원장은 이력.

### 원장은 같은 트랜잭션에서 함께 쓴다

`stock_movement` 는 `available_qty` 를 바꾸는 **모든** 연산과 같은 트랜잭션 안에서 함께 `INSERT` 한다.
따로 쓰면 재고와 이력이 어긋나고, 어긋난 뒤에는 어느 쪽이 맞는지 판단할 근거가 사라진다.
`stock_allocation` 은 예약 상태를 맡고, 이 표는 재고에 관한 모든 사건을 시간순으로 모으는 단일 창구다.

폐기는 처음에 `stock_disposal` 이라는 별도 표에 있었는데 흡수했다.
**폐기하면 `available_qty` 가 줄어 `DISPOSE` 행이 반드시 생기므로, 같은 폐기가 두 표에 기록되고 있었다.**
두 수량이 어긋나도 아무도 보지 않는 자리였다.

```sql
disposal_reason VARCHAR(30) NULL,   -- DISPOSE 일 때만

CHECK ((movement_type =  'DISPOSE' AND disposal_reason IS NOT NULL AND admin_id IS NOT NULL)
    OR (movement_type <> 'DISPOSE' AND disposal_reason IS NULL))
```

컬럼 하나와 CHECK 하나가 별도 표가 하던 일(사유 enum 강제, 처리자 필수)을 그대로 한다.
`claim` 이 `type` 에 따라 `collect_*` 와 `reship_*` 를 채우는 것과 같은 패턴이다.

**`stock_movement` 을 없애고 `stock_disposal` 만 남기는 방향은 검토했다가 버렸다.**
그러면 폐기 외 일곱 가지 변동의 기록이 사라지고, `available_qty` 가 왜 그 값인지 설명할 수 없게 되며,
`daily_sales` 의 집계 원천도 없어진다.

### 원장은 자기 자신을 검증한다

`stock_movement` 는 `available_qty` 가 왜 그 값인지를 설명하는 유일한 근거다.
그런데 오랫동안 **세 수치가 서로 아무 관계도 요구받지 않았다.**

```sql
CHECK (quantity > 0 AND qty_before >= 0 AND qty_after >= 0)   -- 범위만 봤다
```

`qty_before=100, quantity=5, qty_after=3` 인 `RESERVE` 행이 그냥 들어갔다. 원장이 원장 노릇을 못 한다.

**다른 표를 볼 필요가 없는 한 행 안의 항등식**이라 CHECK 로 정확히 표현된다.
`orders.chk_order_total` 이 금액에 대해 하는 일과 같다.

```sql
CONSTRAINT chk_movement_delta CHECK (
    (movement_type IN ('INBOUND','RESTOCK','RELEASE') AND qty_after = qty_before + quantity)
 OR (movement_type IN ('RESERVE','EXPIRE')            AND qty_after = qty_before - quantity)
 OR (movement_type =  'CONFIRM'                       AND qty_after = qty_before)
 OR (movement_type =  'DISPOSE' AND disposal_reason =  'RETURNED' AND qty_after = qty_before)
 OR (movement_type =  'DISPOSE' AND disposal_reason <> 'RETURNED' AND qty_after = qty_before - quantity)
 OR (movement_type =  'ADJUST'  AND (qty_after = qty_before + quantity OR qty_after = qty_before - quantity)))
```

**항등식만이 아니라 유형이 정한 방향까지 본다.** `RESERVE` 가 재고를 늘리는 행도 거부된다.
`ADJUST` 만 양방향이고 나머지는 방향이 하나로 고정된다.
회수품 폐기가 `available_qty` 를 안 바꾼다는 규칙도 여기서 처음 강제된다. 전에는 주석에만 있었다.

`quantity` 를 부호 있는 증감으로 바꿔 CHECK 를 한 줄로 줄이는 안도 있었다.

```sql
quantity INT NOT NULL,  -- + 는 늘고 - 는 준다
CHECK (qty_after = qty_before + quantity)
```

짧지만 **유형별 방향이 DDL 밖으로 나간다.** `RESERVE` 에 `+5` 가 들어가도 통과한다.
`CONFIRM` 은 증감이 0 이라 몇 개를 확정했는지도 사라진다. 원장이 자기만으로 완결되지 않게 되어 접었다.

### 만료는 폐기와 다른 상태다

`stock_movement` 와 `daily_sales` 는 처음부터 둘을 나눠 놓았다.

```
movement_type   DISPOSE 폐기 / EXPIRE 만료전환
daily_sales     disposed_qty / expired_qty
```

**그런데 `stock_lot.status` 에는 만료가 없었다.** `AVAILABLE / SOLD_OUT / DISPOSED` 셋뿐이라
`EXPIRE` 전환이 로트를 어떤 상태로 만드는지 답이 없었다. 셋 중 무엇을 골라도 어긋난다.

| | 왜 안 맞나 |
|---|---|
| `DISPOSED` | 별개라고 해놓고 같은 상태에 넣는다. 로트만 봐서는 버린 건지 기한이 지난 건지 모른다 |
| `SOLD_OUT` | 팔린 것이 아니다 |
| `AVAILABLE` 유지 | 만료됐다는 사실이 어디에도 안 남는다 |

값 하나를 더해 셋을 맞췄다.

```sql
CONSTRAINT chk_lot_status CHECK (status IN ('AVAILABLE','SOLD_OUT','DISPOSED','EXPIRED'))
```

**추가 제약은 필요 없다.** `chk_lot_status_qty` 가 `status = 'AVAILABLE' OR available_qty = 0` 이라
`EXPIRED` 도 자동으로 가용재고 0 을 요구한다.

`EXPIRED` 라는 값이 다른 두 곳에 이미 있어 헷갈리기 쉽다.

```
member_coupon.status            ISSUED / USED / EXPIRED       쿠폰 만료
stock_movement.disposal_reason  EXPIRED / DAMAGED / RETURNED  폐기 사유
```

`disposal_reason='EXPIRED'` 는 **"기한이 지나서 버렸다" 는 폐기 사유**이고,
`movement_type='EXPIRE'` 는 **"기한이 지나 판매 불가로 바뀌었다" 는 사건**이다.
전자는 `DISPOSE` 와 함께 오고 후자는 혼자 온다. 로트 상태 `EXPIRED` 는 후자에 대응한다.

### 판매 차단은 상태가 아니라 조회 조건이 한다

만료 재고가 팔리는 것을 막는 것은 이 상태가 아니다. **배치는 주기가 있어서 항상 늦는다.**
자정에 만료되는 로트를 정확히 그 시각에 전환할 수 없고, 그 사이에 들어온 주문은 상태만 믿으면 그대로 팔린다.

```sql
WHERE l.status = 'AVAILABLE'
  AND l.expiry_date >= DATE_ADD(CURDATE(), INTERVAL p.sale_available_days_from_expiry DAY)
```

**조회 조건에는 그 틈이 없다.** `idx_lot_fefo (product_option_id, status, expiry_date)` 가
날짜를 마지막에 둬서 범위 조건에도 인덱스가 그대로 먹는다.

그래서 상태 전환은 **판매 차단용이 아니라 재고 정리용**이다. `available_qty` 를 0 으로 만들어
품절 판정과 집계가 없는 재고를 세지 않게 하고, 실물 정리 대상을 `status = 'EXPIRED'` 한 줄로 뽑게 한다.

CHECK 로는 이 규칙을 표현할 수 없다. **MySQL 은 CHECK 에 `CURDATE()` 같은 비결정 함수를 금지한다.**
`expiry_date >= received_date` 는 걸 수 있어도 `expiry_date >= 오늘` 은 못 건다.

### 반품은 원래 로트로 되돌린다

소비기한이 로트에 달려 있어 다른 로트에 넣으면 기한을 잃는다.
어느 로트였는지는 `claim_item -> order_item -> stock_allocation` 으로 찾는다.
잔여 소비기한이 `product.sale_available_days_from_expiry` 에 못 미치면 되돌리지 않고 폐기한다(`disposal_reason='RETURNED'`).
그 경우 로트로 돌아간 적이 없으므로 `available_qty` 를 줄이지 않고 `stock_movement` 행도 남지 않는다.

### 집계는 옵션 단위다

`daily_sales` 와 `coupon_product_option` 이 모두 `product_option_id` 를 본다.

**재고와 소비기한이 옵션 단위인데 집계만 상품 단위면 200g 와 1kg 의 수량을 더한 값이 된다.**
그 위에 세운 소진율로 "소비기한 임박 + 판매율 저조" 상품을 고르면 어느 옵션이 임박했는지가 사라지고,
캠페인 대상이 상품이면 1kg 만 임박했는데 200g 에도 쿠폰이 먹어 임박 재고가 안 빠진다.

### 기말 재고 컬럼을 걷어냈다

`daily_sales` 에는 `closing_stock` 이 있었다. 그런데 **다른 컬럼 어느 조합으로도 그 값을 설명할 수 없었다.**

```
closing = opening + inbound + restocked - sold - disposed - expired   <- 성립하지 않는다
```

두 군데가 빈다. `available_qty` 를 실제로 빼는 것은 `RESERVE` 인데 `sold_qty` 는 **결제 완료 기준**이라
마감 시점에 예약만 되고 결제되지 않은 수량만큼 어긋난다. 그리고 `ADJUST`(수동 조정)에 대응하는 컬럼이 아예 없다.

게다가 **`closing_stock` 은 어느 지표에도 쓰이지 않았다.** 소진율 공식에 없고,
다음 날 행의 `opening_stock` 이 이미 같은 값이라 두 곳이 같은 사실을 들고 있었다.

항등식이 성립하도록 `reserved_qty`, `released_qty`, `adjusted_qty` 를 채우는 안도 있었다.
**그러면 이 표가 원장이 하나 더 생긴 꼴이 된다.** `stock_movement` 가 이미 원장인데 요약을 다시 들면
같은 사실을 두 곳이 적게 되고, 카운터가 늘고 정합성 검사가 따라붙는다.
`stock_disposal` 을 `stock_movement` 로 흡수한 것이 정확히 그 이유였다.

그래서 **컬럼을 뺐다.** 이 표는 소진율과 폐기율의 재료라는 한 가지 일만 한다.
재고 대조는 `stock_movement` 의 `qty_before` 와 `qty_after` 로 하고, 그 원장에는 항등식 제약이 걸려 있다.

전제가 하나 붙는다. **배치는 움직임이 없는 옵션에도 행을 만든다.** 날짜가 끊기면
다음 날 `opening_stock` 으로 전날 기말을 읽는 경로가 끊어진다.

---

## 7. 이미지 업로드

업로드가 앱을 거치지 않고 클라이언트가 S3 로 직접 올린다. 그래서 행이 두 시점에 걸쳐 만들어진다.

```
발급   서버가 key 를 정해 행을 INSERT (upload_status='PENDING')
PUT    클라이언트 -> S3
확정   HeadObject 로 존재를 확인하고 CONFIRMED 로 바꾼다
```

**발급 시점에 행을 만드는 이유는 키를 클라이언트에게 받지 않기 위해서다.**
키를 돌려받아 저장하면 남의 키를 실어 보내 남의 이미지를 자기 리소스에 붙일 수 있다.
서명은 **올리는 것**만 막지 **저장하는 것**은 막지 못한다.

그 대가로 "아직 올라오지 않은 행" 이 표에 남고, 조회가 `upload_status='CONFIRMED'` 를 빠뜨리면 깨진 이미지가 나간다.

크기와 `Content-Type` 은 저장하지 않는다. **S3 객체 메타데이터가 진실이고 조회는 브라우저가 직접 받는다.**
상한과 허용 목록은 모든 업로드에 공통인 설정값이지 객체의 속성이 아니다.

세 표(`product_image`, `claim_attachment`, `shipment_photo`)로 나눈 것은 다형 참조를 피하기 위해서다.
한 표에 `owner_type` + `owner_id` 로 담으면 외래 키를 걸 수 없어 고아 행을 DB 가 막지 못한다.
나눠 두니 대표 이미지 소유 검증과 조회 확정 적용 범위를 표 단위로 정할 수 있는 이점도 따라왔다.

자세한 흐름은 `fresh-market/fm-infra` 의 `백엔드공통_이미지저장소_설계.md` 6.2절에 있다.

---

## 8. 상태와 시각을 짝으로 묶는다

```sql
CONSTRAINT chk_payment_paid_at CHECK (
    (status IN ('PENDING','FAILED') AND paid_at IS NULL)
 OR (status IN ('PAID','REFUNDED')  AND paid_at IS NOT NULL)
 OR  status = 'CANCELED')
```

같은 모양이 여섯 곳에 있다.

| 표 | 짝 |
|---|---|
| `payment` | `status='PAID'` 와 `paid_at`, `pg_tid` |
| `shipment` | 세 상태와 `shipped_at`, `delivered_at` |
| `claim` | `REQUESTED` 가 아니면 `processed_at` |
| `refund` | `status='DONE'` 과 `refunded_at` |
| `member_coupon` | `status='USED'` 와 `used_at` |
| `qna` | `status='ANSWERED'` 와 `answer`, `answered_by` |

시각 순서를 보는 CHECK 는 있는데 **상태와 시각의 짝은 없던** 비대칭을 메운 것이다.
`payment.CANCELED` 만 예외인데, 결제 전 취소와 결제 후 취소가 모두 정상이라 한쪽으로 묶을 수 없다.

`member` 와 `admin` 의 `refresh_token_hash` 와 `refresh_token_expires_at` 도 같은 형태다.
해시만 남고 만료가 `NULL` 이면 영구 토큰이 된다.
**두 표가 같은 컬럼과 같은 CHECK 를 갖는다.** 인증 방식은 다르지만(회원은 카카오 OIDC, 관리자는 자체 비밀번호)
세션을 끊는 방법은 같아야 하고, 관리자 세션이 더 위험한 쪽이라 회원보다 느슨할 이유가 없다. 평문을 저장하지 않는 것은 유출이 그대로 계정 탈취가 되기 때문이고,
고엔트로피 난수라 bcrypt 가 아니라 단순 해시로 충분하다. `NULL` 은 로그아웃 상태이며,
액세스 토큰이 stateless 라 **서버가 세션을 끊을 수 있는 유일한 지점**이 이 컬럼이다.

`member` 의 탈퇴도 같은 형태다. `status='WITHDRAWN'` 과 `deleted_at` 이 어긋나면
`active_provider_key` 가 잘못 계산되어 탈퇴자가 재가입을 못 하거나 활성 회원이 중복 가입된다.

`admin` 의 삭제는 한 걸음 더 간다. **삭제된 관리자는 리프레시 토큰도 비어 있어야 한다.**

```sql
CONSTRAINT chk_admin_deleted CHECK (
    (status =  'DELETED' AND deleted_at IS NOT NULL AND refresh_token_hash IS NULL)
 OR (status <> 'DELETED' AND deleted_at IS NULL))
```

토큰이 남아 있으면 **삭제해도 세션이 그대로 살아 있다.** 액세스 토큰이 stateless 라
재발급을 끊는 것 말고는 막을 방법이 없고, 관리자 세션은 회원보다 위험한 쪽이다.
`member` 에는 이 조건이 없다. 탈퇴는 본인이 하는 것이라 세션을 끊는 주체와 대상이 같지만,
관리자 삭제는 남이 하는 것이라 대상이 로그인한 채로 남을 수 있다.

`admin` 은 애초에 **하드 삭제가 불가능한 표**다. 이력 다섯이 `admin_id` 를 참조한다.

```
audit_log.admin_id                     감사 로그
stock_movement.admin_id                수동 조정과 폐기 처리자
claim.processed_by                     클레임 처리자
qna.answered_by                        답변자
member_coupon_status_history.changed_by 쿠폰 상태를 바꾼 사람
```

`login_id` 는 삭제해도 다시 쓰지 않는다. 감사 로그가 `admin_id` 를 가리키므로 이력 자체는 안전하지만,
**같은 아이디가 다른 사람이 되면 로그를 읽는 사람이 헷갈린다.** `product_code` 와 같은 판단이다.
`member` 가 계산 컬럼으로 재가입을 여는 것과는 반대인데, 카카오 계정 재가입은 실제 요구이고 관리자 아이디 재사용은 요구가 아니다.

### 같은 값 목록이 여러 CHECK 에 나뉘어 있다

enum 멤버십 CHECK 가 28건인데 그중 **15건이 다른 곳과 같은 값 목록**이다.

```
3벌  (PENDING, CONFIRMED)          product_image, claim_attachment, shipment_photo
3벌  (ISSUED, USED, EXPIRED)       member_coupon.status + 이력 표의 from/to
3벌  주문 상태 12개                 orders.status + 이력 표의 from/to
2벌  (ON_SALE, SOLD_OUT, OFF_SALE) product, product_option
2벌  (AMOUNT, RATE)                coupon, member_coupon
```

**가장 위험한 자리는 주문 상태다.** 값을 하나 추가할 때 `order_status_history` 를 빠뜨리면
헤더는 새 상태로 바뀌는데 이력 INSERT 가 거부되어 **상태 전이 트랜잭션이 통째로 롤백된다.**
오류는 나지만 원인이 이력 표라는 것을 알아채는 데 시간이 걸린다.

줄이는 방법 셋을 봤고 전부 대가가 있어 **그대로 두기로 했다.**

| | 방법 | 대가 |
|---|---|---|
| **a** | 규칙으로 관리 | 사람이 지킨다 |
| b | MySQL `ENUM` 타입 | 표 간 중복은 그대로다. 값 추가에 `ALTER` 가 필요한 것도 같다. 이식성만 잃는다 |
| c | 상태 코드 참조 표 + 외래 키 | 표가 늘고 상태 하나 읽자고 조인이 생긴다. "상태는 컬럼" 이라는 전제와 어긋난다 |

**b 는 중복을 줄이지 못한다.** `ENUM('PENDING','CONFIRMED')` 을 세 표에 각각 쓰는 것은 CHECK 를 세 번 쓰는 것과 같다.
목록이 컬럼 정의로 자리만 옮길 뿐이다.

그래서 a 로 가되, 규칙을 사람 기억에 두지 않고 **세 곳에 박았다.**

```
DDL 헤더        같은 목록을 쓰는 짝을 전부 나열
이력 표 CHECK   "orders.status 와 같은 집합" 이라는 주석
정합성 검사     목록이 실제로 갈라졌는지 검사
```

검사는 데이터가 아니라 **제약 정의 자체를 비교한다.** 데이터를 보면 그 값이 실제로 쓰인 뒤에야 잡히는데,
정의를 보면 목록이 갈라지는 순간 잡힌다.

```sql
SELECT grp, COUNT(DISTINCT vals) AS lists
  FROM (SELECT REGEXP_REPLACE(
                 REPLACE(REGEXP_REPLACE(check_clause, '`[^`]*`', ''), '_latin1', ''),
                 '[^A-Z_,]', '') AS vals, ... AS grp
          FROM information_schema.check_constraints WHERE constraint_schema = DATABASE()) t
 GROUP BY grp HAVING COUNT(DISTINCT vals) > 1;
```

정규화가 필요하다. `check_clause` 에는 컬럼명(`` `from_status` ``)과 문자열 도입자(`_latin1`)가 섞여 있어
그대로 비교하면 값 목록이 같아도 다르게 나온다. 백틱 식별자와 `_latin1` 을 지우고 **대문자와 밑줄, 쉼표만 남기면**
값 목록만 남는다.

MySQL 8.4 에 올려 확인했다. 다섯 묶음이 전부 `같음` 으로 나오고,
`orders.status` 에만 값을 하나 더해 보면 그 묶음이 `갈라짐` 으로 바뀐다.

`(ORDER, ITEM)` 은 원래 `coupon` 과 `member_coupon` 두 벌이었는데
`chk_mc_scope` 를 지우면서 한 벌이 됐다. **복합 외래 키가 값 검사까지 대신하면 중복이 자연히 사라진다.**
다른 자리에도 같은 길이 있는지는 외래 키로 묶이는 값인지에 달려 있고, 스냅샷 복사본은 그럴 수 없다.

### 상태 컬럼은 대소문자를 구분한다

서버 기본 콜레이션이 `utf8mb4_0900_ai_ci` 라 **대소문자를 구분하지 않는다.**
그래서 `'canceled'` 가 `CHECK (status IN ('CANCELED', ...))` 를 통과했다.

```sql
'order' = 'ORDER'             -> 1
'order' IN ('ORDER','ITEM')   -> 1
```

**DB 안에서는 깨지지 않는다.** 계산 컬럼도 같은 콜레이션으로 비교하므로 소문자 `'canceled'` 를 취소로 인식했고,
`active_coupon_key` 는 정상적으로 `NULL` 이 됐다. 외래 키도 소문자를 부모와 같은 값으로 봤다.

문제는 밖이다. 애플리케이션이 `Enum.valueOf("canceled")` 를 부르면 예외가 난다.
**DB 는 받아 주는데 자바가 터진다.** 조용히 틀리는 것보다는 낫지만, 막을 수 있는 자리에서 안 막은 것이다.

값 집합이 정해진 문자열 컬럼 **34개에만** 대소문자를 구분하는 콜레이션을 붙였다.

```sql
status VARCHAR(30) COLLATE utf8mb4_0900_as_cs NOT NULL
```

**이름과 주소 같은 자유 문자열은 기본값을 그대로 둔다.** 상품명 검색이 대소문자를 구분하면 안 되기 때문이다.
표 전체나 서버 기본값을 바꾸면 그 검색까지 함께 바뀐다.

복합 외래 키로 묶인 `scope` 넷(`coupon`, `coupon_product_option`, `member_coupon`, `orders.coupon_scope`)은
**콜레이션이 서로 달라지면 외래 키 생성 자체가 실패한다.** 한 벌로 함께 바꿔야 하는 이유다.

| 값 | 전 | 후 |
|---|---|---|
| `member.status = 'active'` | 통과 | `chk_member_status` 거부 |
| `coupon.scope = 'order'` | 통과 | `chk_coupon_scope` 거부 |
| `member_coupon.scope = 'order'` | 통과 | `fk_mc_coupon` 거부 |
| `orders.status = 'canceled'` | 통과 | `chk_order_status` 거부 |
| `member_grade.name = '브론즈'` 검색 | 맞음 | 맞음 (기본값 유지) |

### 재배송은 승인된 교환에만 있다

`claim` 은 `type` 에 따라 `collect_*` 와 `reship_*` 를 채운다. 여기에 상태 조건을 하나 더 걸었다.

```sql
CONSTRAINT chk_claim_reship_type CHECK (
    (type = 'EXCHANGE' AND status IN ('APPROVED','COMPLETED'))
 OR (reship_* 전부 NULL))
```

**거부됐거나 아직 접수 상태인 교환에 재배송 송장이 붙는 것**을 막는다. 새 상품을 보내놓고 거부한 셈이 되기 때문이다.

**회수(`collect_*`)에는 같은 조건을 걸지 않았다.** 이 흐름이 정상이기 때문이다.

```
반품 신청 -> 고객이 회수 발송 -> 창고 도착 -> 확인해보니 사유 불충족 -> REJECTED
```

거부된 클레임에 `collect_delivered_at` 이 있는 것은 사실에 맞다. 물건은 실제로 왔다.
승인 전에 물건이 먼저 도착하는 것도 운영에 따라 정상이라 상태를 걸면 정상 흐름을 막는다.

**두 배송이 대칭으로 생겼다고 제약도 대칭이어야 하는 것은 아니다.** 회수는 고객이 시작하고 재배송은 우리가 시작한다.

### 소프트딜리트도 상태와 짝이다

`deleted_at` 을 가진 표가 넷인데 상태와 묶인 것은 `member` 뿐이었다.

```
member   status='WITHDRAWN' <-> deleted_at IS NOT NULL   묶여 있다
product  deleted_at 이 있어도 sale_status='ON_SALE' 로 남는다
review   deleted_at 이 있어도 is_public=TRUE 로 남는다
qna      status(WAITING/ANSWERED) 는 삭제와 다른 축이라 묶을 것이 없다
```

`product` 만 묶었다.

```sql
CONSTRAINT chk_product_deleted CHECK (deleted_at IS NULL OR sale_status = 'OFF_SALE')
```

**삭제한 상품이 판매중 상태로 남지 않는다.** 조회가 `deleted_at IS NULL` 을 빠뜨려도 `sale_status` 로 한 번 더 걸린다.
되살릴 때 `OFF_SALE` 로 남아 사람이 다시 켜야 하는 것은 안전한 방향이라 그대로 뒀다.

`review` 는 걸지 않았다. `is_public` 은 **사용자가 정한 값**이라 삭제하면서 강제로 내리면 복원할 때 원래 의도가 사라진다.
`sale_status` 는 운영이 정하는 값이라 성격이 다르다.

### 자식 쪽 외래 키 인덱스는 선언하지 않는다

복합 외래 키를 걸 때 **부모 쪽 참조 대상 UNIQUE 는 반드시 명시하지만**(그게 없으면 외래 키를 만들 수 없다),
자식 쪽 인덱스는 선언하지 않고 MySQL 이 자동 생성하는 것에 맡긴다.

```
order_item   복합 외래 키 넷    자동 생성 넷
claim_item   둘               자동 생성 둘
payment      하나             자동 생성 하나
```

그래서 `payment` 처럼 `uk_payment_order (order_id)` 와 자동 생성된 `(order_id, amount)` 가
**같은 컬럼으로 시작하는 인덱스 둘**이 되는 곳이 생긴다. 쓰기 비용이 조금 늘지만 기능에는 문제가 없다.

전부 명시하면 인덱스 선언이 열 개 넘게 늘고, **DDL 에 드러나는 것과 실제가 같아지는 것 말고 얻는 것이 없다.**
`uk_payment_order` 를 `(order_id, amount)` 로 합치는 방법은 쓸 수 없다.
`refund` 가 `payment (order_id)` 를 참조하므로 `order_id` 단독 UNIQUE 가 필요하다.

---

## 9. DB 가 못 막아서 앱이 지켜야 하는 것

CHECK 는 **자기 행만** 볼 수 있다. 다른 행이나 다른 표를 봐야 하는 조건은 원리적으로 표현할 수 없다.

### 두 외래 키의 조합은 대부분 DB 가 막는다

각각의 외래 키가 유효해도 조합이 틀릴 수 있다. **리뷰가 A 상품을 가리키는데 근거인 주문 상품은 B 상품인 경우**가 그렇다.

이 문제의 뿌리는 모든 표가 대리 키를 쓴다는 것이다. 자연 키 설계였다면 조상 키가 자식으로 전파되어
`claim_item` 의 키에 `order_id` 가 이미 들어 있었을 것이고, 다른 주문의 항목을 넣을 수가 없었을 것이다.
대리 키를 쓰면 그 전파가 끊긴다. **복합 외래 키는 끊긴 전파를 선택적으로 되살리는 표준 기법**이고,
복제한 값이 외래 키로 강제되므로 어긋날 수 없다는 점에서 일반적인 비정규화와 성격이 다르다.

여섯 중 넷은 **애초에 중복이라 컬럼을 지워 없앴다.** 저장하지 않으면 틀릴 수 없다.

| 없앤 컬럼 | 어디서 유도되나 |
|---|---|
| `refund.payment_id` | `claim.order_id -> payment`. `uk_payment_order` 가 주문당 결제 1건을 보장한다. 뒤에 `order_id` 를 복제해 결제를 직접 참조하게 했지만 `payment_id` 는 여전히 저장하지 않는다 |
| `stock_disposal.product_id` | `stock_lot -> product_option -> product`. `stock_lot_id` 를 `NOT NULL` 로 바꿔 유도가 항상 성립했다. 그 뒤 표 자체가 `stock_movement` 로 흡수됐다 |

나머지는 복합 외래 키 열로 막는다.

| 표 | 공유하는 조상 키 | 막는 것 |
|---|---|---|
| `claim_item` | `order_id` | 다른 주문의 주문 상품을 클레임에 넣는 것 |
| `orders` | `member_id` | 남의 쿠폰을 주문에 붙이는 것 |
| `order_item` | `member_id`, `coupon_id` | 남의 쿠폰을 붙이는 것, 대상이 아닌 옵션에 쓰는 것 |
| `review` | `product_option_id`, `member_id` | 다른 상품에 리뷰를 쓰거나 남의 구매로 쓰는 것 |
| `coupon_product_option` | `scope` | 장바구니 쿠폰에 대상 옵션을 다는 것 |
| `member_coupon` | `scope` | 발급분의 범위가 쿠폰과 어긋나는 것 |
| `orders` | `coupon_scope` | 상품 쿠폰을 장바구니 쿠폰 자리에 넣는 것 |
| `payment` | `total_amount` | 결제 금액이 주문 최종금액과 다른 것 |
| `refund` | `order_id` | 다른 주문의 결제에 환불을 다는 것, 결제 없는 주문에 환불을 만드는 것 |

`review` 는 사슬이 둘이다. `order_item` 이 `product_id` 를 갖지 않고 `product_option_id` 만 갖기 때문에
가운데 고리로 `product_option_id` 를 복제해야 두 외래 키가 이어진다.

```
review(order_item_id, product_option_id, member_id) -> order_item
review(product_option_id, product_id)               -> product_option
```

`product_id` 를 지우고 조인으로 유도할 수도 있었지만, 상품별 리뷰 목록이 상품 상세마다 도는 경로라
**컬럼 하나를 더해 조회 성능을 지키고 강제도 얻는 쪽**을 택했다.

**다만 `product_id` 로 시작하는 인덱스는 아직 없다.** 조인 하나를 없앤 것까지가 지금 얻은 것이고,
`(product_id, created_at)` 같은 보조 인덱스는 넣지 않았다. 실제 조회 패턴과 데이터량을 보고 정하기로 미뤘다.
`member_id` 도 같다. `qna` 는 외래 키 덕에 그 경로가 열려 있어 지금은 둘이 비대칭이다.

부모마다 참조 대상 UNIQUE 가 하나씩 필요하다. `claim_id` 처럼 이미 PK 인 컬럼이라도
그 조합에 인덱스가 있어야 외래 키 대상이 될 수 있다.

### 목록 검사도 외래 키로 옮겼다

"이 라인의 옵션이 그 쿠폰의 대상 목록에 있는가" 는 언뜻 `EXISTS` 검사라 외래 키로 표현할 수 없어 보인다.
그런데 **목록 자체가 표이므로 그 표를 참조하면 된다.**

```sql
order_item.coupon_id 를 복제하고
  FOREIGN KEY (member_coupon_id, coupon_id)    -> member_coupon      그 쿠폰이 맞는지
  FOREIGN KEY (coupon_id, product_option_id)   -> coupon_product_option  그 대상에 이 옵션이 있는지
```

두 번째 외래 키가 참조할 행이 없으면 `INSERT` 가 거부된다. **대상이 아닌 옵션에는 쿠폰이 붙지 않는다.**

정확히는 **"한 번도 대상이었던 적이 없는 옵션"** 까지다. 대상에서 뺄 때 행을 지우지 않고
`is_active` 를 내리므로(5장), 내려간 대상은 외래 키를 여전히 통과한다. 거기부터는 앱이 `is_active` 를 본다.
막으려던 사고가 관리자의 옵션 번호 오타라서 이 정도로 충분하다고 봤다. 오타는 한 번도 대상이 아니었던 옵션을 가리킨다.

전제는 **`ITEM` 쿠폰이 대상 옵션을 반드시 하나 이상 갖는 것**이다.
원래는 "행이 없으면 대상 제한 없음" 이었는데 그 규칙을 버렸다.
**부재를 의미로 쓰면 사고가 조용하다.** 관리자가 대상을 실수로 전부 지우면 전 상품 할인이 되고,
데이터가 사라진 것인지 원래 없었던 것인지 구분할 수 없다.
아무 상품에나 쓰는 쿠폰이 필요하면 `scope='ORDER'` 가 그 자리다.

`coupon_product_option` 쪽도 같은 기법이다. `scope` 를 복제해 `CHECK (scope = 'ITEM')` 으로 못 박고
`FOREIGN KEY (coupon_id, scope) -> coupon (coupon_id, scope)` 를 걸면
장바구니 쿠폰은 이 표에 행을 가질 수 없다.

**결과로 `DI-3-05` 로 남는 조합 검증이 없다.** 여섯 중 둘은 컬럼을 지워 없앴고 넷은 외래 키로 막는다.

### 행의 부재는 DB 가 못 막는다

`ITEM` 쿠폰이 **활성** 대상 옵션을 하나 이상 갖는다는 전제를 위에서 세웠는데, **그것을 강제하는 제약이 없다.**

```
coupon INSERT (scope='ITEM')   통과. 대상 행이 없어도 된다
member_coupon 발급             통과
order_item 이 쓰려 할 때        복합 외래 키가 거부
```

만들 수도 있고 발급도 되는데 쓸 때 실패한다. **실수한 사람은 관리자인데 발견하는 사람은 사용자다.**

DB 로 옮길 수 없는 이유는 순환이다. 대상 행은 `coupon_id` 를 적어야 하므로 쿠폰이 먼저 들어가야 하고,
그 순간 대상은 반드시 0 개다. **0 개인 순간을 통과시켜야 하니 0 개를 금지할 수 없다.**
트랜잭션 끝에 검사하면 풀리지만 MySQL 에는 지연 제약(`DEFERRABLE INITIALLY DEFERRED`)이 없다.

기수별로 표현 수단이 갈린다.

| 자식 개수 | 표현 | 강제 |
|---|---|---|
| 정확히 1 | 부모의 컬럼 + `NOT NULL` | 된다 |
| 0 이상 | 별도 표 + 외래 키 | 된다 |
| **1 이상** | 별도 표 + 외래 키 | **수단이 없다** |

관계형 모델의 한계라 표를 어떻게 나눠도 같다. 그래서 막는 대신 **기본값을 안전한 쪽으로 돌렸다.**

```sql
is_active BOOLEAN NOT NULL DEFAULT FALSE   -- 이전에는 TRUE 였다
```

쿠폰이 초안으로 태어난다. 대상을 안 넣고 나가도 발급되지 않는다.
켜는 것이 별도 행위가 되고, 그 자리에서 대상 존재를 검사한다.

**"있으면 안 되는 행" 이 "켜면 안 되는 상태" 로 바뀐다.** 상태 전이는 원래 앱이 지키는 부류라 예외가 늘지 않는다.
트리거로 삭제와 발급을 막는 안도 있었지만, 생성 구멍은 트리거로도 못 막아 앱 검증이 어차피 남는다.
경로를 둘로 늘릴 이유가 없어 버렸다.

정합성 검사(`DI-7-01`)는 켜진 쿠폰만 본다. 꺼진 초안은 대상이 없어도 정상이다.

```sql
SELECT c.coupon_id, c.name
  FROM coupon c
 WHERE c.scope = 'ITEM' AND c.is_active
   AND NOT EXISTS (SELECT 1 FROM coupon_product_option o WHERE o.coupon_id = c.coupon_id AND o.is_active);

-- 기본 등급이 정확히 1개인가
SELECT COUNT(*) FROM member_grade WHERE is_default;
```

같은 형태가 넷 더 있다.

| 자리 | 지금 상태 | 풀 방법 |
|---|---|---|
| `member_grade` | 기본 등급이 하나도 없어도 된다. UNIQUE 는 **최대** 1개만 막는다 | 정합성 검사. 등급은 사람이 드물게 건드리는 표라 이걸로 충분하다 |
| `product` | 옵션 없이 `sale_status='ON_SALE'` 로 태어난다 | 같은 수법. 기본값을 `OFF_SALE` 로 |
| `orders` | 라인이 하나도 없어도 들어간다 | 초안 상태가 성립하지 않는다. 라인 없이 주문만 저장하는 코드를 못 쓰게 하는 쪽 |
| `claim` | 항목이 하나도 없어도 들어간다 | 같음 |

`member_grade` 외 셋은 **아직 손대지 않았다.**

### CHECK 는 결과가 `NULL` 이면 통과한다

제약이 걸린 줄 알았는데 안 걸리는 자리가 있었다. CHECK 는 **`FALSE` 일 때만 거부하고 `NULL` 은 통과시킨다.**

```sql
-- 고치기 전
(member_coupon_id IS NOT NULL AND coupon_scope = 'ORDER')
OR (member_coupon_id IS NULL  AND coupon_scope IS NULL)
```

`member_coupon_id` 만 채우고 `coupon_scope` 를 비우면 첫 가지가 `NULL`, 둘째 가지가 `FALSE` 라
전체가 `NULL` 이 되어 **통과한다.** 복합 외래 키도 컬럼에 `NULL` 이 끼면 검사하지 않으므로
`ITEM` 쿠폰을 장바구니 쿠폰 자리에 넣는 것이 그대로 뚫려 있었다. 앞에서 막았다고 적어 둔 바로 그 경로다.

가지 안에 `IS NOT NULL` 을 명시해서 닫았다.

```sql
(member_coupon_id IS NOT NULL AND coupon_scope IS NOT NULL AND coupon_scope = 'ORDER')
OR (member_coupon_id IS NULL  AND coupon_scope IS NULL)
```

**규칙은 이렇다.** 가지가 여럿인 CHECK 에서 `NULL` 가능 컬럼을 비교 연산에 쓰면,
그 가지 안에 그 컬럼의 `IS NOT NULL` 이 함께 있어야 한다.
`chk_mc_issue_seq` 를 쓸 때 `issue_seq IS NOT NULL` 을 넣은 이유도 같다.

`x IS NULL OR y IS NULL OR y >= x` 처럼 `NULL` 을 **명시적으로 통과시키려는** CHECK 는 이 규칙과 무관하다.
문제는 통과시킬 의도가 없었는데 통과하는 경우다.

### 삭제된 부모에 자식이 붙는 것은 못 막는다

`member` 와 `product` 는 소프트딜리트다. **행이 남아 있으므로 외래 키가 통과시킨다.**
탈퇴한 회원에게 새 주문을 만들거나 삭제된 상품에 새 옵션을 다는 것을 DB 가 거부하지 않는다. 자식이 여덟이다.

```
member  <- address, cart, member_coupon, orders, qna
product <- product_option, product_image, qna
```

계산 컬럼으로 막으려 하면 **정확히 반대 방향으로 부러진다.**

```sql
member.active_member_id GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN member_id ELSE NULL END)
orders.member_id FOREIGN KEY -> member (active_member_id)
```

탈퇴하는 순간 계산 컬럼이 `NULL` 이 되어 **기존 주문의 참조 대상이 사라지고 탈퇴 자체가 막힌다.**
주문 이력은 법정 기간 보존해야 하므로 자식을 지울 수도 없다.
쿠폰 대상 옵션에서 겪은 것과 같은 형태이고, 살아 있는 값을 이력의 참조 대상으로 삼으면 늘 이렇게 된다.

`product` 는 더하다. 삭제된 상품의 옵션을 주문 라인이 참조하므로 어떤 방법으로도 지울 수 없다.

**자식 중 부모와 함께 없어져도 되는 것(`cart`, `address`)만은 이 방법이 통한다.**
탈퇴할 때 먼저 지우면 되고, 그러면 `RESTRICT` 가 삭제 순서를 강제하는 장치가 된다.
넣지 않은 이유는 **탈퇴 시 장바구니와 배송지를 파기하는 것이 정책인지 정해지지 않았기 때문**이다.
보관하는 정책이면 이 외래 키가 정상 탈퇴를 막는다. 정해지면 추가만 하는 마이그레이션으로 얹는다.

지금은 정합성 검사가 받는다. **부모가 삭제된 뒤에 생긴 자식**을 `created_at` 비교로 찾으므로
삭제 전에 만들어진 정상 이력은 오탐하지 않는다.

실제 위험도는 경로에 따라 다르다. **탈퇴하면 `refresh_token_hash` 가 비워지고 `status='WITHDRAWN'` 이라 로그인이 안 된다.**
사용자 경로로는 새 주문이 생길 수 없고 남는 것은 관리자 API 나 배치의 실수다.
`product` 쪽이 더 현실적이다. 관리자 화면이 삭제된 상품을 목록에서 안 빼면 옵션과 이미지가 계속 붙는다.

### 자식 행 합계 (`DI-3-06`)

행 하나씩은 유효한데 합이 넘는 경우다. **CHECK 는 자기 행만 보므로 원리적으로 표현할 수 없다.**

클레임 수량이 그런 자리였다. 3개 산 것을 2개짜리 반품 두 건으로 나누면 각 행은 정상이고 합만 넘는다.
**`claim_item` 에서 수량을 없애 이 문제를 만들지 않기로 했다.**

클레임은 라인 단위로 걸고 부분 수량을 지정하지 않는다.
중복은 `order_item.item_status` 조건부 UPDATE 가 막는다.

```sql
UPDATE order_item SET item_status = 'RETURN_REQ'
 WHERE order_item_id = ? AND item_status = 'ORDERED';
-- affected rows 0 이면 이미 클레임이 걸린 라인이다
```

수량을 유지하려면 `order_item` 에 카운터를 하나 더 만들어야 했다.
`stock_lot.available_qty`, `coupon.issued_quantity` 에 이어 세 번째이고, 각각 되돌리기 경로와 정합성 검사가 따라붙는다.
**부분 수량 반품이 필요해지면 `claim_item.qty` 와 `order_item.claimed_qty` 를 추가만 하는 마이그레이션으로 넣는다.**

뒤에 두 개를 더 찾았는데 **둘 다 DB 로 옮겨서 여기 남지 않았다.**
`payment.amount` 대 `orders.total_amount` 는 값 하나 대 값 하나라 복합 외래 키로,
환불 총액 대 결제액은 진짜 합계라 `payment.refunded_amount` 카운터로 처리했다(4장).
**합계라고 다 앱으로 내려오지 않는다. 대응하는 값이 하나면 외래 키가 받는다.**

남는 것은 둘이고 성질이 같다.

| 위치 | 확인할 것 |
|---|---|
| `orders.product_amount` | `SUM(order_item.unit_price * qty)` 와 같은가 |
| `orders.discount_amount` | `SUM(order_item.discount_amount)` 와 같은가 |

**이 둘은 클레임 수량과 성질이 다르다.** 주문 생성 한 트랜잭션 안에서 `orders` 와 라인을 함께 쓰므로
다투는 상대가 없다. **동시성 위험이 아니라 계산 버그**이고, 그래서 잠금도 카운터도 답이 아니다.

구분해 두지 않으면 나중에 여기에도 카운터를 만들거나 잠금을 걸게 된다.

증분으로 채울 수도 없다. `order_item` 이 `orders` 를 참조하므로 `orders` 가 먼저 들어가야 하고,
`chk_order_total` 은 그 행이 들어가는 순간부터 성립해야 한다. **주문을 넣는 시점에 이미 합계를 알고 있어야 한다.**

`product_amount` 를 없애 문제를 지울 수도 없다. 그러면 `chk_order_total` 이 사라져
**관리자가 `total_amount` 만 고치는 것**도 못 막게 된다. 지금 그 CHECK 가 실제로 잡는 것이 부분 수정이다.

정합성 검사(`DI-7-01`)가 쿼리 하나로 둘을 함께 본다.

```sql
SELECT o.order_id, o.product_amount, SUM(i.unit_price * i.qty) AS line_sum,
       o.discount_amount, SUM(i.discount_amount) AS line_discount
  FROM orders o
  JOIN order_item i ON i.order_id = o.order_id
 GROUP BY o.order_id, o.product_amount, o.discount_amount
HAVING o.product_amount  <> SUM(i.unit_price * i.qty)
    OR o.discount_amount <> SUM(i.discount_amount);
```

어긋난 주문이 나오면 그 주문을 만든 코드에 버그가 있다는 뜻이고, 동시성 때문이 아니라 재현과 수정이 쉽다.

### 조건부 유일성 중 앱으로 내린 것

없다. 넷은 계산 컬럼으로, 둘은 재사용을 포기해 평범한 UNIQUE 로 처리했다.

### 앱이 지켜야 할 것 한눈에

DB 로 옮길 수 있는 것은 옮겼고, 남은 것이 이만큼이다. **전부 코드가 아직 없다.**

| 자리 | 규칙 | 근거 |
|---|---|---|
| 쿠폰 활성화 | `scope='ITEM'` 인데 활성 대상이 0 개면 거부 | 9장 행의 부재 |
| 쿠폰 적용 | `coupon_product_option.is_active` 인 대상만 허용 | 5장 |
| 대상 조정 | `DELETE` 가 아니라 `is_active` 를 내린다 | 5장 |
| 선착순 발급 | 조건부 `UPDATE` 로 올린 `issued_quantity` 를 `issue_seq` 에 넣는다 | 5장 |
| 환불 | `payment.refunded_amount` 를 조건부 `UPDATE` 로 올린다 | 4장 |
| 주문 취소 | `member_coupon.status` 를 `CANCELED` 로 바꾸고 `used_at` 을 비운다 | 5장 |
| 쿠폰함 조회 | `CANCELED` 를 만료 여부에 따라 `EXPIRED` 나 `ISSUED` 로 해소한다. 조회 지점 전부에 적용 | 5장 |
| 주문 취소 | `order_item.item_status` 도 함께 `CANCELED` 로 바꾼다 | 5장. 라인 계산 컬럼이 `orders.status` 를 보지 않는다 |
| 주문 상태 | `CANCELED` 와 `RETURNED` 는 전체에만 쓴다. 부분 반품은 라인만 바꾼다 | 5장. 두 계산 컬럼이 이 전제 위에 선다 |
| 소프트딜리트 | 삭제된 부모에 새 자식을 만들지 않는다(탈퇴 회원의 주문, 삭제된 상품의 옵션과 이미지) | 9장. 외래 키는 행이 남아 있어 통과시킨다 |
| 재고 변동 | `available_qty` 를 바꾸는 연산과 `stock_movement` INSERT 를 한 트랜잭션에 | 6장 |
| 클레임 | `order_item.item_status` 조건부 `UPDATE` 로 중복을 막는다 | 9장 |
| 부모와 자식 | `orders` 와 `claim` 은 라인 없이 저장하는 경로를 만들지 않는다 | 9장 행의 부재 |
| 상태 전이 | 주문, 결제, 배송, 클레임의 전이 규칙 전반 | 8장 |
| 이미지 | 조회는 `CONFIRMED` 만, 확정은 HeadObject 로 | 7장 |

### 정합성 검사 한눈에 (`DI-7-01`)

막지는 못하고 어긋난 것을 찾는다. 배치가 주기적으로 돌린다.

```sql
-- 1. 주문 합계가 라인 합과 맞는가
SELECT o.order_id FROM orders o JOIN order_item i ON i.order_id = o.order_id
 GROUP BY o.order_id, o.product_amount, o.discount_amount
HAVING o.product_amount <> SUM(i.unit_price * i.qty) OR o.discount_amount <> SUM(i.discount_amount);

-- 2. 켜진 ITEM 쿠폰인데 활성 대상이 없는가
SELECT c.coupon_id FROM coupon c
 WHERE c.scope = 'ITEM' AND c.is_active
   AND NOT EXISTS (SELECT 1 FROM coupon_product_option o WHERE o.coupon_id = c.coupon_id AND o.is_active);

-- 3. 기본 등급이 정확히 1개인가
SELECT COUNT(*) FROM member_grade WHERE is_default;

-- 4. 발급 카운터가 실제 발급 수와 맞는가 (초과 발급은 순번이 막지만 카운터 자체는 어긋날 수 있다)
SELECT c.coupon_id, c.issued_quantity, COUNT(m.member_coupon_id) AS actual
  FROM coupon c LEFT JOIN member_coupon m ON m.coupon_id = c.coupon_id
 GROUP BY c.coupon_id, c.issued_quantity HAVING c.issued_quantity <> COUNT(m.member_coupon_id);

-- 5. 환불 카운터가 환불 합과 맞는가
SELECT p.payment_id, p.refunded_amount, COALESCE(SUM(r.amount), 0) AS actual
  FROM payment p LEFT JOIN refund r ON r.order_id = p.order_id
 GROUP BY p.payment_id, p.refunded_amount HAVING p.refunded_amount <> COALESCE(SUM(r.amount), 0);

-- 6. 헤더가 종료 상태인데 살아 있는 라인이 있는가 (계산 컬럼의 전제가 깨졌다)
SELECT o.order_id FROM orders o
 WHERE o.status IN ('CANCELED','RETURNED')
   AND EXISTS (SELECT 1 FROM order_item i
                WHERE i.order_id = o.order_id AND i.item_status NOT IN ('CANCELED','RETURNED'));

-- 7. 할당과 원장이 맞는가 (stock_movement 에 order_item_id 가 없어 주문+로트 단위까지만 대조된다)
SELECT i.order_id, a.stock_lot_id, SUM(a.qty) AS alloc, COALESCE(m.net, 0) AS ledger
  FROM stock_allocation a
  JOIN order_item i ON i.order_item_id = a.order_item_id
  LEFT JOIN (SELECT order_id, stock_lot_id,
                    SUM(CASE WHEN movement_type = 'RESERVE' THEN quantity
                             WHEN movement_type = 'RELEASE' THEN -quantity ELSE 0 END) AS net
               FROM stock_movement GROUP BY order_id, stock_lot_id) m
    ON m.order_id = i.order_id AND m.stock_lot_id = a.stock_lot_id
 WHERE a.status IN ('RESERVED','CONFIRMED')
 GROUP BY i.order_id, a.stock_lot_id, m.net
HAVING SUM(a.qty) <> COALESCE(m.net, 0);

-- 8. 발급 수가 한도를 넘었는가 (issue_seq 가 막는 불변식을 직접 확인한다)
SELECT coupon_id, COUNT(*) AS issued, MAX(issue_limit) AS cap
  FROM member_coupon
 GROUP BY coupon_id
HAVING MAX(issue_limit) IS NOT NULL AND COUNT(*) > MAX(issue_limit);

-- 9. 배송비 차감이 실제 배송비를 넘었는가
SELECT r.refund_id, r.shipping_deduction, o.shipping_fee
  FROM refund r JOIN orders o ON o.order_id = r.order_id
 WHERE r.shipping_deduction > o.shipping_fee;

-- 10. 부모가 삭제된 뒤에 생긴 자식이 있는가 (created_at 비교라 정상 이력은 잡히지 않는다)
SELECT 'address' AS child, a.address_id AS id FROM address a
  JOIN member m ON m.member_id = a.member_id
 WHERE m.deleted_at IS NOT NULL AND a.created_at > m.deleted_at
UNION ALL
SELECT 'cart', c.cart_id FROM cart c
  JOIN member m ON m.member_id = c.member_id
 WHERE m.deleted_at IS NOT NULL AND c.created_at > m.deleted_at
UNION ALL
SELECT 'member_coupon', mc.member_coupon_id FROM member_coupon mc
  JOIN member m ON m.member_id = mc.member_id
 WHERE m.deleted_at IS NOT NULL AND mc.created_at > m.deleted_at
UNION ALL
SELECT 'orders', o.order_id FROM orders o
  JOIN member m ON m.member_id = o.member_id
 WHERE m.deleted_at IS NOT NULL AND o.created_at > m.deleted_at
UNION ALL
SELECT 'qna(member)', q.qna_id FROM qna q
  JOIN member m ON m.member_id = q.member_id
 WHERE m.deleted_at IS NOT NULL AND q.created_at > m.deleted_at
UNION ALL
SELECT 'qna(product)', q.qna_id FROM qna q
  JOIN product p ON p.product_id = q.product_id
 WHERE p.deleted_at IS NOT NULL AND q.created_at > p.deleted_at
UNION ALL
SELECT 'product_option', o.product_option_id FROM product_option o
  JOIN product p ON p.product_id = o.product_id
 WHERE p.deleted_at IS NOT NULL AND o.created_at > p.deleted_at
UNION ALL
SELECT 'product_image', i.product_image_id FROM product_image i
  JOIN product p ON p.product_id = i.product_id
 WHERE p.deleted_at IS NOT NULL AND i.created_at > p.deleted_at;

-- 11. 같은 값 목록을 쓰는 CHECK 들이 갈라졌는가 (데이터가 아니라 제약 정의를 본다)
--     검사 대상 묶음이 갈라지면 lists 가 2 이상이 된다
SELECT grp, COUNT(*) AS checks, COUNT(DISTINCT vals) AS lists
  FROM (SELECT REGEXP_REPLACE(
                 REPLACE(REGEXP_REPLACE(check_clause, '`[^`]*`', ''), '_latin1', ''),
                 '[^A-Z_,]', '') AS vals,
               CASE
                 WHEN constraint_name IN ('chk_order_status','chk_osh_to_status','chk_osh_from_status') THEN 'order_status'
                 WHEN constraint_name IN ('chk_mc_status','chk_mcsh_to_status','chk_mcsh_from_status') THEN 'coupon_status'
                 WHEN constraint_name IN ('chk_product_sale_status','chk_option_sale_status') THEN 'sale_status'
                 WHEN constraint_name IN ('chk_coupon_discount_type','chk_mc_discount_type') THEN 'discount_type'
                 WHEN constraint_name IN ('chk_product_image_status','chk_claim_attachment_status',
                                          'chk_shipment_photo_status') THEN 'upload_status'
               END AS grp
          FROM information_schema.check_constraints
         WHERE constraint_schema = DATABASE()) t
 WHERE grp IS NOT NULL
 GROUP BY grp
HAVING COUNT(DISTINCT vals) > 1;

-- 12. 소비기한이 지났거나 판매 기준에 못 미치는데 아직 팔리는 로트가 있는가
--     EXPIRE 배치가 멈추면 여기서 드러난다. 판매 차단 자체는 FEFO 조회 조건이 한다
SELECT l.stock_lot_id, l.expiry_date, p.sale_available_days_from_expiry
  FROM stock_lot l
  JOIN product_option o ON o.product_option_id = l.product_option_id
  JOIN product p ON p.product_id = o.product_id
 WHERE l.status = 'AVAILABLE'
   AND l.expiry_date < DATE_ADD(CURDATE(), INTERVAL p.sale_available_days_from_expiry DAY);

-- 13. 카테고리가 순환하는가 (재귀 CTE 로 자기 자신에 도달하는 경로를 찾는다)
WITH RECURSIVE walk (root_id, cur_id, depth) AS (
    SELECT category_id, parent_id, 1 FROM category WHERE parent_id IS NOT NULL
    UNION ALL
    SELECT w.root_id, c.parent_id, w.depth + 1
      FROM walk w JOIN category c ON c.category_id = w.cur_id
     WHERE c.parent_id IS NOT NULL AND w.depth < 20
)
SELECT DISTINCT root_id FROM walk WHERE cur_id = root_id;

-- 14. 자식이 하나도 없는 부모가 있는가
SELECT 'order' AS kind, o.order_id AS id FROM orders o
 WHERE NOT EXISTS (SELECT 1 FROM order_item i WHERE i.order_id = o.order_id)
UNION ALL
SELECT 'claim', c.claim_id FROM claim c
 WHERE NOT EXISTS (SELECT 1 FROM claim_item i WHERE i.claim_id = c.claim_id)
UNION ALL
SELECT 'product', p.product_id FROM product p
 WHERE p.sale_status = 'ON_SALE'
   AND NOT EXISTS (SELECT 1 FROM product_option o WHERE o.product_id = p.product_id);
```

**`issue_limit` 은 검사 대상이 아니다.** `coupon.total_quantity` 와 같은지 보려다 접었다.
한정 수량을 늘리면 옛 발급분이 다른 값을 갖는 것이 정상이라 매번 오탐이 난다.
정책을 불변으로 두기로 했으므로 실제로 갈릴 일은 드물지만, **갈려도 그것이 옳은 상태**라 검사로 잡을 값이 아니다.
대신 8 번이 **불변식 자체**를 본다.

**4 와 5 는 카운터가 생기면 따라붙는 짝이다.** 카운터를 두는 대가가 이 검사이고,
`stock_lot.available_qty` 도 같은 성질이라 `stock_movement` 의 `qty_before` / `qty_after` 로 되짚을 수 있다.

---

## 10. 삽입 후 바뀌면 안 되는 컬럼

**CHECK 는 이전 값을 볼 수 없다.** "한 번 채웠으면 못 비운다" 는 전이 규칙이라 제약으로 표현할 수 없다.
엔티티에서 `@Column(updatable = false)` 로 막는다. 필드에 한 번 선언하면 Hibernate 가 `UPDATE` 문의
`SET` 절에서 그 컬럼을 아예 빼므로, **호출 지점마다 규율이 필요한 조건부 UPDATE 와 성격이 다르다.**

`updatable` 은 필드 단위라 같은 엔티티의 다른 컬럼은 그대로 갱신된다.
`orders.status` 는 전이하고 `orders.member_coupon_id` 는 고정되는 식이다.
엔티티 전체를 잠그는 `@Immutable` 은 이력 표들(`order_status_history`,
`member_coupon_status_history`, `stock_movement`, `audit_log`)에만 어울린다.

### 주문 시점 스냅샷

바뀌면 과거가 왜곡된다.

```
orders         order_no, member_id, ordered_at, product_amount,
               member_coupon_id, coupon_scope, coupon_discount
order_item     name_snapshot, option_name_snapshot, unit_price, qty, coupon_discount
member_coupon  coupon_id, member_id, coupon_name, scope, discount_type,
               discount_value, max_discount_amount, min_order_amount, valid_from, valid_to
```

### 조상 키 복제

바뀌면 **복합 외래 키가 다른 부모를 가리키게 된다.**

```
order_item              order_id, member_id, product_option_id, coupon_id, member_coupon_id
claim_item              claim_id, order_item_id, order_id
review                  order_item_id, product_option_id, product_id, member_id
coupon_product_option   coupon_id, scope
orders                  coupon_scope
refund                  claim_id, order_id
payment                 order_id, amount
```

`payment.amount` 는 조상 키가 아니라 조상의 **값**을 복제한 것인데 성질이 같다.
`orders.total_amount` 와 복합 외래 키로 묶여 있어 바꾸면 참조가 깨진다.
결제가 붙은 뒤 주문 금액을 고칠 수 없게 만드는 것이 이 복제의 목적이기도 하다.

`claim_item.order_id` 를 바꾸면 두 복합 외래 키가 새 값으로 다시 검사되고,
그 값을 만족하는 다른 클레임과 다른 주문 상품 조합이 있으면 통과한다. **클레임이 통째로 다른 주문으로 옮겨간다.**

### 정하지 않은 것

```
orders.ship_recipient, ship_phone, ship_zipcode, ship_address, ship_message
```

주문 시점 스냅샷이지만 **출고 전까지 고칠 수 있는 값**이라 성격이 다르다.
배송지 변경 기능을 둘지에 따라 불변 여부가 갈린다.

---

## 11. 의도적으로 넣지 않은 것

| | 이유 |
|---|---|
| 외부 노출 식별자 (`public_id`) | API 가 설계되지 않아 어느 표가 단독 지목 대상인지 답할 수 없다. 도입할 때는 추가만 하는 마이그레이션으로 얹는다 |
| 포인트 | 적립 시점, 소멸 정책, 잔액을 원장 합으로 낼지 컬럼으로 둘지가 정해지지 않았다 |
| 등급 할인 | `member_grade` 는 등급 구분만 하고 혜택을 갖지 않는다. 혜택 형태를 정할 때 함께 본다 |
| 알림 | 발송 대상 리소스를 가리키는 참조 형태가 정해지지 않았다 |
| 부분 반품 후 쿠폰 조건 위반 처리 | 안분액만 회수할지, 쿠폰을 무효화하고 재계산할지 정하지 않았다. 후자는 추가 결제가 필요해질 수 있다 |

---

## 12. 검증 상태

**지금 형태로는 아직 실행된 적이 없다.** 정적 검사만 거쳤다.

MySQL 8.4 컨테이너에 올려 표를 만들고 제약이 실제로 동작하는지 확인한 적은 있으나,
그것은 표가 35 개이고 쿠폰이 캠페인 구조이던 시점이다. 그 이후 이만큼이 바뀌었다.

```
쿠폰 재설계             캠페인 표 제거, member_coupon 이 조건을 복사
쿠폰 조건 복사 철회      정책 불변 전제로 일곱 컬럼을 참조로 되돌림
포인트와 등급 할인 제거
폐기 표 흡수            stock_disposal 이 stock_movement 로 들어감
DATETIME(6) 전환        시각 컬럼 79 개
복합 외래 키 13 건
계산 컬럼 6 개
```

현재 규모는 **표 32, 제약 136, 인덱스 50** 이다.

정적으로 확인한 것은 이렇다. 매번 스크립트로 다시 돌린다.

```
제약과 인덱스 이름 중복 0        PK 누락, 트레일링 콤마 0
정의 순서 역전 0                 끊긴 참조 0
복합 외래 키 대상 UNIQUE 누락 0
의도 밖 NULL 통과 CHECK 0
연속 빈 줄 0                     금지 문자 0
```

마지막 검증 라운드에서 여덟 건을 찾아 모두 닫았다.
기본 등급 삽입 실패, 쿠폰 대상 조정 차단, 결제액 무검증, 환불 총액 초과,
0 원 주문 환불, 기본 등급 부재, 리뷰 보조 인덱스 부재, 연속 빈 줄이다.
성격이 하나로 모였는데 **적어 둔 의도와 실제 DDL 이 어긋난 자리**였다.

**복합 외래 키와 계산 컬럼은 실행해야 확실해진다.** 참조 대상 UNIQUE 가 전부 맞는지,
계산 컬럼이 참조하는 컬럼 순서가 MySQL 요구를 만족하는지는 정적으로 확인했지만
`CREATE TABLE` 이 실제로 통과하는지는 다른 문제다.

Flyway 로 한 번에 실행하는 것이 다음 순서다.
