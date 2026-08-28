# 상품

상품, 카테고리, 리뷰, Q&A 다. 재고와 로트는 [stock.md](./stock.md) 에 있다.

**판매 단위는 상품이 아니라 옵션(`product_option`)이다.** 가격과 재고가 옵션에 붙는다.

## 회원

### 상품 목록

```
GET /v1/products?categoryId={id}&sort=SALES_DESC&pageSize=20
```

| 파라미터 | 허용값 | 기본 |
|---|---|---|
| `categoryId` | 카테고리 | 전체 |
| `sort` | `SALES_DESC`, `CREATED_DESC`, `PRICE_ASC`, `PRICE_DESC` | `SALES_DESC` |
| `minPriceKrw`, `maxPriceKrw` | 정수(원) | 없음 |

**삭제된 상품은 나오지 않는다.** 품절은 목록에서 빼지 않고 표시만 한다.

```json
{
  "products": [
    {
      "productId": 12,
      "name": "제주 감귤 1kg",
      "category": { "categoryId": 4, "name": "과일" },
      "minPriceKrw": 12900,
      "saleStatus": "ON_SALE",
      "soldOut": false,
      "mainImageUrl": "https://cdn.example.com/products/ab/3f9c.jpg"
    }
  ],
  "nextPageToken": "eyJ..."
}
```

`soldOut` 은 가용 재고가 0인지로 서버가 계산한다. **클라이언트가 재고 수치로 판단하지 않는다.**

### 상품 검색

```
GET /v1/products:search?query={키워드}&categoryId={id}&sort=...
```

상품명 부분 일치다. 목록과 같은 필터와 정렬을 받는다.

**검색어를 쿼리에 문자열로 붙이지 않는다** (`SEC-2-01`). 정렬 키는 화이트리스트로 검증한다.

### 소비기한 임박 상품

```
GET /v1/products:expiringSoon?categoryId={id}&pageToken=&pageSize=20
```

| 파라미터 | 기본 | 설명 |
|---|---|---|
| `categoryId` | 없음 | 카테고리 필터 |
| `pageSize` | 20 | 페이지 크기. 최대 100 |

**자정 배치가 확정한 그날의 떨이 쿠폰 대상 상품을 돌려준다.** 관리자용
[캠페인 대상 조회](./statistics.md)와 같은 확정본(`campaign_target_lot`)을 읽으므로,
**캠페인 대상과 회원에게 노출되는 상품이 어긋날 수 없다.** 같은 기준일에는 항상 같은 목록이다.

**`withinDays` 파라미터가 없다.** 대상 구간은 배치가 확정할 때 이미 정해지고 이 API 는 그 결과를
읽기만 하기 때문이다. 회원이 구간을 넓히거나 좁힐 수 있으면 쿠폰 대상이 아닌 상품까지 섞여 나온다.

구간 기준은 이렇다. 소비기한이 `N` 이면:

```
N-13 ─────────── N-10 ─────────── N
 임박 시작        판매 마감         소비기한
      └─ 캠페인 대상 구간 ─┘
```

`N-10`(판매 마감 기한)은 상품의 `sale_available_days_from_expiry`, `N-13`은 그 앞 임박 기간(3일)이다.
**판매 마감이 지난 로트는 대상이 아니다** — 팔 수 없는 재고에 쿠폰을 붙여도 쓸 수가 없다.

### 상품 상세

```
GET /v1/products/{productId}
```

```json
{
  "productId": 12,
  "productCode": "P-2026-0012",
  "name": "제주 감귤 1kg",
  "description": "...",
  "category": { "categoryId": 4, "name": "과일" },
  "storageType": "COLD",
  "saleStatus": "ON_SALE",
  "options": [
    { "productOptionId": 31, "name": "1kg", "priceKrw": 12900, "saleStatus": "ON_SALE", "soldOut": false }
  ],
  "images": [
    { "productImageId": 88, "url": "https://...", "isMain": true, "sortOrder": 0 }
  ],
  "review": { "count": 24, "averageRating": 4.5 }
}
```

**`CONFIRMED` 상태의 이미지만 나온다.** 업로드가 끝나지 않은 것은 목록에서 뺀다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `404` | `PRODUCT-001` | 없거나 삭제된 상품 |

### 카테고리 목록

```
GET /v1/categories
```

수산물, 육류, 채소, 과일, 유제품이 초기값이다. 상위 카테고리를 둘 수 있는 구조이나 지금은 한 단계다.

## 리뷰

### 목록

```
GET /v1/products/{productId}/reviews?pageSize=20
```

평점 평균과 건수를 함께 준다. **비공개 리뷰와 삭제된 리뷰는 나오지 않는다.**

### 작성

```
POST /v1/products/{productId}/reviews
```

```json
{
  "orderItemId": 501,
  "rating": 5,
  "title": "달아요",
  "content": "가족들이 잘 먹었습니다.",
  "isPublic": true
}
```

| 필드 | 필수 | 제약 |
|---|---|---|
| `orderItemId` | O | 본인의 구매 건 |
| `rating` | O | 1 ~ 5 |
| `content` | O | 빈 값 불가 |

**구매 건당 한 번이다.** DB 가 `order_item` 단위 UNIQUE 로 강제한다.
복합 외래 키가 주문 상품, 옵션, 상품, 구매자를 묶어 **남의 구매나 다른 상품에 쓸 수 없다.**

| 오류 | 코드 | 언제 |
|---|---|---|
| `403` | `REVIEW-001` | 본인 구매가 아니다 |
| `409` | `REVIEW-002` | 이미 작성했다 |
| `422` | `REVIEW-003` | 구매 완료 상태가 아니다 |

### 수정과 삭제

```
PATCH  /v1/reviews/{reviewId}
DELETE /v1/reviews/{reviewId}
```

**본인 작성분만 가능하다.** 삭제는 소프트 딜리트라 목록에서 즉시 빠지고,
**지운 리뷰의 주문 상품에 다시 쓸 수 없다.**

## Q&A

### 목록

```
GET /v1/products/{productId}/questions
```

**비공개 글은 작성자와 관리자에게만 보인다.** 목록 쿼리에 소유자 조건이 들어간다 (`SEC-1-03`).

### 작성, 수정, 삭제

```
POST   /v1/products/{productId}/questions
PATCH  /v1/questions/{qnaId}
DELETE /v1/questions/{qnaId}
```

| 필드 | 필수 | 제약 |
|---|---|---|
| `title` | O | 255자 이하 |
| `question` | O | 빈 값 불가 |
| `isPublic` | | 기본 `true` |

| 오류 | 코드 | 언제 |
|---|---|---|
| `409` | `QNA-001` | 이미 답변된 질문은 수정할 수 없다 |
| `403` | `QNA-002` | 본인 글이 아니다 |

## 관리자

### 상품 목록

```
GET /v1/admin/products?query=&categoryId=&saleStatus=&includeDeleted=true
```

**판매안함, 품절, 삭제까지 전부 본다.** 재고는 로트 잔량 합계로 표시한다.

### 등록

```
POST /v1/admin/products
```

```json
{
  "name": "제주 감귤 1kg",
  "requestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "categoryId": 4,
  "supplierId": 2,
  "storageType": "COLD",
  "saleAvailableDaysFromExpiry": 3,
  "description": "...",
  "options": [ { "name": "1kg", "price": 12900 } ]
}
```

| 필드 | 필수 | 제약 |
|---|---|---|
| `name` | O | 255자 이하 |
| `requestId` | O | 클라이언트가 생성하는 요청 식별자. 100자 이하 |
| `categoryId` | O | 존재하는 카테고리 |
| `supplierId` | O | 존재하는 공급처 |
| `storageType` | O | `ROOM`, `COLD`, `FROZEN` |
| `saleAvailableDaysFromExpiry` | | 0 이상. 기본 0 |
| `options[].price` | O | 0 이상 |

**상품코드는 서버가 만든다** (`API-2-06`). 클라이언트가 보내지 않는다.
**재고와 소비기한은 여기서 받지 않는다.** 로트 입고로만 들어온다.
**같은 `requestId`로 재시도하면 새로 등록하지 않고 최초 등록 결과를 그대로 돌려준다** (`API-5-07`, `AIP-155`).

### 수정과 삭제

```
PATCH  /v1/admin/products/{productId}
DELETE /v1/admin/products/{productId}
```

판매중이어도 수정할 수 있고 즉시 반영된다. 삭제는 소프트 딜리트다.

**삭제하면 판매 상태가 `OFF_SALE` 이어야 한다.** DB 가 CHECK 로 강제한다.
되살릴 때는 사람이 다시 켠다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `409` | `PRODUCT-002` | 진행 중 주문이 있다 |
| `409` | `PRODUCT-003` | 잔여 재고가 있는 로트가 있다. 폐기 등록이 먼저다 |
| `409` | `PRODUCT-004` | 진행 중 주문이 있으면 상품코드를 바꿀 수 없다 |

### 이미지

```
POST   /v1/admin/products/{productId}/images:createUploadUrl
POST   /v1/admin/products/{productId}/images/{imageId}:confirm
DELETE /v1/admin/products/{productId}/images/{imageId}
```

**두 단계다.** 업로드 URL 을 발급받아 클라이언트가 S3 에 직접 올리고, 완료를 통지하면 서버가
`HeadObject` 로 확인한 뒤 `CONFIRMED` 로 바꾼다.

```json
{ "productImageId": 88, "uploadId": "018f...", "uploadUrl": "https://s3...", "objectKey": "products/ab/3f9c.jpg" }
```

**URL 을 통째로 저장하지 않는다.** 객체 키만 저장하고 도메인은 환경 설정에서 붙인다.
확장자, MIME 타입, 크기를 검증하고 **파일명은 서버가 만든다** (`SEC-3-04`).

대표 이미지는 상품당 하나다. 교체할 때는 옛 대표를 먼저 내려야 한다.

### 벌크 등록

```
POST /v1/admin/products:bulkCreate
```

CSV 를 받아 여러 상품을 한 번에 만든다. **형식 오류 행을 리포트로 돌려준다.**

```json
{
  "created": 120,
  "failed": [ { "row": 15, "reason": "가격이 음수다" } ]
}
```

### 카테고리 관리

```
POST   /v1/admin/categories
PATCH  /v1/admin/categories/{categoryId}
DELETE /v1/admin/categories/{categoryId}
```

| 오류 | 코드 | 언제 |
|---|---|---|
| `409` | `CATEGORY-001` | 소속 상품이 있다 |
| `409` | `CATEGORY-002` | 같은 부모 아래 이름 중복 |

**순환 참조는 DB 로 막을 수 없다.** A 의 부모가 B, B 의 부모가 A 인 경우는 재귀 검사라
정합성 검사가 잡는다.

### 리뷰와 Q&A 관리

```
PATCH  /v1/admin/reviews/{reviewId}
DELETE /v1/admin/reviews/{reviewId}
POST   /v1/admin/questions/{qnaId}:answer
PATCH  /v1/admin/questions/{qnaId}
DELETE /v1/admin/questions/{qnaId}
```

답변을 등록하면 상태가 `ANSWERED` 로 바뀌고 작성자에게 알림이 나간다.
**답변 완료 상태는 본문과 답변자를 함께 갖는다.** DB 가 CHECK 로 강제한다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `422` | `QNA-003` | 빈 답변 |
| `409` | `QNA-004` | 삭제된 글에는 답변할 수 없다 |
