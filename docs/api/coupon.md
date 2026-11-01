# 쿠폰

쿠폰함 조회, 선착순 발급, 관리자의 쿠폰 관리다.

**쿠폰은 둘로 나뉜다.** 이 구분이 경로와 적용 방식을 가른다.

| `scope` | 붙는 자리 | 개수 |
|---|---|---|
| `ORDER` | 주문 전체 (장바구니 쿠폰) | 주문당 1장 |
| `ITEM` | 주문 라인 하나 (상품 쿠폰) | 라인당 1장 |

**종류가 다른 쿠폰의 중복 적용은 안 된다.** 상품 할인과 장바구니 쿠폰을 함께 쓸 수 없다.

## 회원

### 내 쿠폰함

```
GET /v1/members/me/coupons?status=ISSUED
```

| `status` | 뜻 |
|---|---|
| `ISSUED` | 발급됨. 사용 가능 |
| `USED` | 사용함 |
| `EXPIRED` | 만료 |
| `CANCELED` | 주문 취소로 사용이 철회됨 |

```json
{
  "coupons": [
    {
      "memberCouponId": 77,
      "couponName": "소비기한 임박 30% 할인",
      "scope": "ITEM",
      "discountType": "RATE",
      "discountValue": 30,
      "maxDiscountAmount": 10000,
      "minOrderAmount": 20000,
      "validFrom": "2026-08-17",
      "validTo": "2026-08-20",
      "status": "ISSUED"
    }
  ]
}
```

**발급 시점의 조건이 그대로 남는다.** 관리자가 나중에 쿠폰 정의를 고쳐도 이미 발급된 것은 안 바뀐다.

`CANCELED` 는 조회할 때 서버가 `ISSUED` 나 `EXPIRED` 로 풀어서 보여준다. 유효기간이 남았으면
다시 쓸 수 있다는 뜻이다.

### 사용 가능한 쿠폰 조회

```
GET /v1/members/me/coupons:applicable?productOptionIds=31,42&orderAmount=25800
```

주문서에서 쓴다. **최소 주문 금액과 대상 옵션을 서버가 판정해서** 쓸 수 있는 것만 돌려준다.

`ITEM` 쿠폰은 대상 옵션 목록이 따로 있다. 그 목록에 없는 옵션에는 붙지 않는다.

### 선착순 발급

```
POST /v1/coupons/{couponId}:issue
```

```
Idempotency-Key: <UUID>
```

**요청 본문이 없다.** 누가 받는지는 토큰으로 정해진다.

```json
{
  "code": "SUCCESS",
  "data": { "memberCouponId": 77, "issueSeq": 3120, "validTo": "2026-08-20" }
}
```

`issueSeq` 는 선착순 순번이다. 한정 수량 쿠폰에만 있고 무제한 쿠폰은 `null` 이다.

**보장하는 것이 셋이다.**

```
초과 발급 0건    조건부 UPDATE 로 발급 수를 원자적으로 올린다
1인 1매         (coupon_id, member_id) UNIQUE
멱등            같은 키로 다시 오면 최초 결과를 그대로 돌려준다
```

재고 10,000장에 20,000명이 동시에 요청해도 초과 발급이 나오지 않아야 한다.
순번은 `(coupon_id, issue_seq)` UNIQUE 와 `issue_seq <= issue_limit` CHECK 가 함께 막는다.
**`coupon.issued_quantity` 가 어긋나도 이 두 제약이 초과 발급을 잡는다.**

| 오류 | 코드 | 언제 |
|---|---|---|
| `409` | `COUPON-001` | 이미 발급받았다. **`data` 에 기존 발급분을 담아 준다** |
| `409` | `COUPON-002` | 재고 소진 |
| `422` | `COUPON-003` | 오픈 전이다 |
| `422` | `COUPON-004` | 발급 마감 |
| `403` | `COUPON-005` | 대상 등급이 아니다 |

**재요청은 오류가 아니라 최초 결과 반환에 가깝다.** `409` 로 내되 본문에 발급분을 담아,
클라이언트가 다시 조회하지 않아도 되게 한다.

### 발급 현황

```
GET /v1/coupons/{couponId}/issuance-status
```

```json
{ "totalQuantity": 10000, "issuedQuantity": 8231, "remaining": 1769 }
```

**캐시에서 읽는다.** 오픈 직후 이 경로가 DB 를 직접 때리면 발급 경로와 자원을 다툰다.

## 관리자

### 쿠폰 목록과 생성

```
GET  /v1/admin/coupons?isActive=&scope=
POST /v1/admin/coupons
```

```json
{
  "name": "소비기한 임박 30% 할인",
  "scope": "ITEM",
  "discountType": "RATE",
  "discountValue": 30,
  "maxDiscountAmount": 10000,
  "minOrderAmount": 20000,
  "totalQuantity": 10000,
  "issueStartAt": "2026-08-17T11:00:00",
  "issueEndAt": "2026-08-17T23:59:59",
  "validFrom": "2026-08-17",
  "validTo": "2026-08-20",
  "targetGradeId": null,
  "productOptionIds": [31, 42, 55]
}
```

| 필드 | 필수 | 제약 |
|---|---|---|
| `scope` | O | `ORDER` 또는 `ITEM` |
| `discountType` | O | `AMOUNT` (정액 원) 또는 `RATE` (정률 %) |
| `discountValue` | O | 0 초과. **정률은 100 이하** |
| `maxDiscountAmount` | | **정률에만 붙는다.** 정액이면 반드시 `null` |
| `totalQuantity` | | 있으면 선착순, 없으면 무제한 |
| `productOptionIds` | `ITEM` 이면 O | 적용 대상 옵션 |

**정률 쿠폰에 상한이 없으면 고액 주문에서 할인이 무제한이 된다.** 그래서 `maxDiscountAmount` 를
정률에만 붙이도록 DB 가 CHECK 로 강제한다.

생성 직후에는 `isActive` 가 `false` 다. **초안으로 태어나 사람이 켠다.**

### 활성화

```
POST /v1/admin/coupons/{couponId}:activate
POST /v1/admin/coupons/{couponId}:deactivate
```

**`ITEM` 쿠폰은 활성 대상 옵션이 하나 이상 있어야 켤 수 있다.** 이 검사는 스프링 서버가 한다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `422` | `COUPON-006` | `ITEM` 쿠폰인데 대상 옵션이 없다 |
| `422` | `COUPON-007` | 유효기간이 시작일보다 이르다 |

### 대상 옵션 관리

```
POST   /v1/admin/coupons/{couponId}/target-options
DELETE /v1/admin/coupons/{couponId}/target-options/{productOptionId}
```

**대상에서 빼도 행을 지우지 않는다.** `is_active` 를 내릴 뿐이다.
`order_item` 이 그 조합을 복합 외래 키로 참조해서, 지우면 이미 팔린 주문에서 삭제가 막힌다.

### 발급 이력 조회

```
GET /v1/admin/coupons/{couponId}/issues?status=&pageSize=
GET /v1/admin/member-coupons/{memberCouponId}/history
```

상태 전이 이력을 준다. **`reason` 이 만료와 어뷰징 취소를 가른다.** 상태값만으로는 갈리지 않는다.

```json
{
  "history": [
    { "fromStatus": null, "toStatus": "ISSUED", "reason": null, "changedBy": null, "createdAt": "..." },
    { "fromStatus": "ISSUED", "toStatus": "EXPIRED", "reason": "유효기간 도래", "changedBy": null, "createdAt": "..." }
  ]
}
```

`changedBy` 가 `null` 이면 배치나 사용자 동작에 의한 자동 전이다.

### 정합성 검증

```
POST /v1/admin/coupons/{couponId}:verifyConsistency
```

발급 이력과 재고가 어긋나지 않는지 확인한다. **300만 건 전체를 대상으로 하고, 같은 데이터로
재실행하면 같은 결과가 나와야 한다.**

```json
{
  "issuedQuantityOnCoupon": 10000,
  "actualIssueCount": 10000,
  "duplicatedMembers": 0,
  "seqGaps": [],
  "consistent": true
}
```

**불일치가 나오면 관리자에게 알린다.**

## 취소와 복원

주문을 취소하거나 반품이 승인되면 쿠폰이 돌아온다.

```
사용 -> 취소     status 가 CANCELED 로 바뀌고 used_at 이 비워진다
유효기간 남음   다시 쓸 수 있다
유효기간 지남   복원하지 않고 그 할인액을 환불금액에 포함한다
교환           결제 금액이 그대로라 복원하지 않는다
```

**`CANCELED` 로 바꿀 때 `used_at` 을 함께 비운다.** 사용이 철회됐으므로 사용 시각도 남지 않는다.
언제 썼었는지는 이력 테이블이 갖는다.

살아 있는 주문 한정으로 쿠폰당 1건임을 DB 가 계산 컬럼과 UNIQUE 로 강제한다.
취소하면 그 값이 `NULL` 이 되어 같은 쿠폰을 다시 쓸 수 있다.

## 정하지 못한 것

| 항목 | 내용 |
|---|---|
| 대상 상품 수 | 기획 1에서 임박 상품 중 몇 개를 쿠폰 대상으로 선정할지 |
| 오픈 예약 | 캠페인 오픈 시각 예약 발급은 선택 확장 항목이다 |
| 현황 조회 캐시 | 캐시 기반 조회로 DB 직접 조회를 막는 것이 선택 확장 항목이다 |

쿠폰 유효기간은 **발급일로부터 3일**로 정해졌다.
