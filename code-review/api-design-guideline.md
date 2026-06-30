# API 설계 리뷰 가이드 (Google AIP 기반)

이 문서는 Google의 API Improvement Proposals(AIP)가 제시하는 API 설계 원칙을 코드 리뷰 점검 항목으로 정리한 가이드다.
API 정의가 추가되거나 변경되는 PR(Controller, 요청과 응답 DTO, proto 정의, OpenAPI 명세)에 적용한다.

각 항목은 AIP의 원칙을 바탕으로 하되 설명과 예시는 이 가이드에서 새로 작성했고, 원문을 찾아볼 수 있도록 AIP 번호를 함께 적었다.
AIP 본문은 Creative Commons Attribution 4.0 라이선스로 공개되며, 출처는 Google API Improvement Proposals다.

AIP는 본래 RPC와 protocol buffers를 기준으로 하지만, 그 원칙은 HTTP와 REST로 매핑된다(AIP-127).
이 가이드는 Spring 기반 REST API 관점에서 읽을 수 있게 정리했고, proto 전용 항목은 그 점을 표시했다.

각 항목 뒤의 AIP 번호는 사람이 원문에서 근거를 찾아볼 때 쓰는 참고 표기다. 원문 URL은 문서 끝 참고 문헌에 모았다.
자동 리뷰 봇은 이 번호나 링크를 따라가지 않고, 아래 본문에 적힌 점검 내용만으로 판단한다.

## 1. 핵심 개념과 프로세스

배경이 되는 메타 문서들이다. 직접적인 코드 점검보다는 설계의 전제를 잡는 데 참고한다.

* 설계 리뷰는 선례를 존중하며 일관성을 우선한다 (AIP-1, AIP-200)
* 용어는 글로서리의 정의를 따른다 (AIP-9)
* 관리 평면(리소스 생성과 설정)과 데이터 평면(실제 데이터 처리)을 구분해 설계한다 (AIP-111)
* 베타 단계에서 막아야 하는 변경을 사전에 식별한다 (AIP-205)

## 2. 리소스 설계

API의 기본 단위는 동작이 아니라 리소스(명사)다. (AIP-121)

점검 항목
* API를 리소스 계층(컬렉션과 하위 컬렉션)으로 모델링했는가 (AIP-121)
* API 표면을 내부 DB 스키마와 동일하게 노출하지 않았는가 (AIP-121)
  DB 구조를 그대로 드러내면 표면이 내부 구현에 강하게 묶인다.
* 리소스 이름이 계층을 드러내는 경로 형식인가 (AIP-122)
  예: `publishers/{publisher}/books/{book}`. 컬렉션 ID는 복수형을 쓴다.
* 리소스 간 참조와 부모 자식 관계가 순환 없이 단방향(비순환 그래프)인가 (AIP-121, AIP-124)
* enum을 적절히 정의하고 0번 값을 unspecified로 두었는가 (AIP-126)
* 서버가 채우는 값과 기본값을 명확히 구분했는가 (AIP-129)
* 단일 인스턴스 리소스는 싱글톤으로 설계했는가 (AIP-156)

```
# 리소스 지향 경로 설계 (AIP-122)
GET    /v1/publishers/123/books/456      # 단건 조회
GET    /v1/publishers/123/books          # 목록
POST   /v1/publishers/123/books          # 생성
PATCH  /v1/publishers/123/books/456      # 부분 수정
DELETE /v1/publishers/123/books/456      # 삭제
```

## 3. 표준 메서드와 오퍼레이션

리소스마다 적은 수의 표준 메서드를 우선 제공하고, 맞지 않을 때만 커스텀 메서드를 쓴다. (AIP-130)

점검 항목
* 표준 메서드(Get, List, Create, Update, Delete)로 표현 가능한 동작을 커스텀으로 만들지 않았는가 (AIP-130)
* 리소스가 최소한 Get을 지원하는가, 그리고 싱글톤이 아니면 List도 지원하는가 (AIP-121)
* Get은 부수 효과가 없는가 (AIP-131)
* List는 페이지네이션을 제공하는가 (AIP-132, AIP-158)
* Create는 부모 컬렉션에 대해 수행하고 생성된 리소스를 반환하는가 (AIP-133)
* Update는 필드 마스크로 부분 수정을 지원하는가 (AIP-134, AIP-161)
* Delete의 응답과 멱등성, 그리고 소프트 삭제 여부를 검토했는가 (AIP-135, AIP-164)
* 커스텀 메서드는 표준 HTTP 동사(주로 POST)에 콜론 표기로 동사를 붙였는가 (AIP-136)
  예: `POST /v1/publishers/123/books/456:publish`
* 오래 걸리는 작업은 장기 실행 오퍼레이션으로 처리하고 폴링 가능한 핸들을 반환하는가 (AIP-151)
* 여러 건을 한 번에 처리하는 배치 메서드는 전부 성공 또는 전부 실패(원자성)를 따르는가 (AIP-231~235)

```java
// 표준 메서드의 자연스러운 매핑 (AIP-131~135)
@RestController
@RequestMapping("/v1/publishers/{publisherId}/books")
public class BookController {

    @GetMapping("/{bookId}")            // Get
    public BookResponse get(...) { ... }

    @GetMapping                         // List (페이지네이션 포함)
    public ListBooksResponse list(...) { ... }

    @PostMapping                        // Create
    public BookResponse create(...) { ... }

    @PatchMapping("/{bookId}")          // Update (부분 수정)
    public BookResponse update(...) { ... }

    @DeleteMapping("/{bookId}")         // Delete
    public void delete(...) { ... }
}
```

## 4. 필드

필드 이름은 단순하고 직관적이며 API 전체에서 일관되어야 한다. (AIP-140)

점검 항목
* 필드 이름이 같은 개념에 같은 이름을 쓰는가 (AIP-140)
* 전치사를 넣지 않았는가 (AIP-140)
  예: `error_reason`(권장), `reason_for_error`(지양)
* 필드 이름이 동사가 아니라 명사인가 (AIP-140)
  필드는 의도가 아니라 값을 나타낸다. `disabled`(권장), `disable`(지양)
* 반복 필드는 복수형, 단일 필드는 단수형인가 (AIP-140, AIP-144)
* boolean 필드에 "is" 접두어를 붙이지 않았는가 (AIP-140)
  예: `disabled`(권장), `is_disabled`(지양)
* 수량 필드는 단위 접미어를 붙였는가 (AIP-141)
  예: `distance_km`, `width_px`. 비율에는 "per"를 쓴다.
* 시간과 기간을 표준 타입으로 표현하고 이름을 `*_time`, `*_duration`으로 두었는가 (AIP-142)
* 언어, 통화, 국가 등은 표준 코드(BCP-47, ISO 4217, ISO 3166)를 쓰는가 (AIP-143)
* 범위 필드의 포함과 배제 경계를 명확히 했는가 (AIP-145)
* 임의 구조 데이터(Struct, Any 등)는 꼭 필요할 때만 제한적으로 쓰는가 (AIP-146)
* 민감 정보는 입력 전용으로 두고 응답에 반환하지 않는가 (AIP-147)
* 표준 필드(name, create_time, update_time, uid, display_name 등)는 정해진 의미와 타입으로 쓰는가 (AIP-148)
* 값의 미설정과 기본값을 구분해야 하는 경우를 처리했는가 (AIP-149)
* 필드 동작(REQUIRED, OPTIONAL, OUTPUT_ONLY, IMMUTABLE, INPUT_ONLY)을 문서화했는가 (AIP-202, AIP-203)
* 상태 필드는 `state`로 두고 출력 전용이며 전이 규칙을 문서화했는가, 상태에 동작을 섞지 않았는가 (AIP-216)

```
# 필드 이름은 proto에서 lower_snake_case, JSON에서는 매핑된 표기를 따른다 (AIP-140)
# Java/Spring REST에서 JSON 표기(camelCase 또는 snake_case)는 팀 정책을 따르되,
# 전치사 금지, 명사 사용, 복수형 규칙 등은 표기와 무관하게 적용한다.
{
  "displayName": "Clean Code",
  "pageCount": 464,
  "disabled": false,
  "createTime": "2026-01-01T00:00:00Z"
}
```

## 5. 설계 패턴

자주 쓰이는 상황에는 검증된 표준 패턴을 따른다.

점검 항목
* 컬렉션 반환은 처음부터 페이지네이션을 넣었는가 (AIP-158)
  나중에 추가하면 호환성이 깨진다. `page_size`, `page_token` 요청 필드와 `next_page_token` 응답 필드를 쓴다.
* page_token은 클라이언트가 해석할 수 없는 불투명 문자열인가 (AIP-158)
* 컬렉션 횡단 조회는 부모에 와일드카드(`-`)를 쓰는가 (AIP-159)
* 필터링은 `filter` 문자열 필드로 표준 문법을 따르는가 (AIP-160)
* 부분 수정과 부분 응답에 필드 마스크를 쓰는가 (AIP-161, AIP-157)
* 갱신 손실을 막기 위해 etag로 리소스 신선도를 검증하는가 (AIP-154)
* 재시도 시 중복 처리를 막기 위해 요청 식별자(request_id)로 멱등성을 보장하는가 (AIP-155)
* 실제 반영 없이 검증만 하는 validate_only(드라이런)를 제공하는가 (AIP-163)
* 삭제는 소프트 삭제로 처리하고 undelete, 만료, show_deleted 목록 옵션을 검토했는가 (AIP-164)
* 조건 기반 일괄 삭제는 위험을 고려해 신중히 설계했는가 (AIP-165)
* 가져오기와 내보내기는 커스텀 메서드(:import, :export)로 표현했는가 (AIP-153)
* 반복 실행 단위는 잡(Job) 패턴으로 분리했는가 (AIP-152)
* 리소스 만료가 필요하면 expire_time이나 ttl을 제공하는가 (AIP-214)
* 일부 위치를 조회하지 못할 수 있으면 List 응답에 unreachable을 두는가 (AIP-217)
* 인가를 먼저 검사하고, 그다음 존재 여부를 확인하는가 (AIP-211, AIP-193)
* 텍스트는 UTF-8로 다루는가 (AIP-210)

```
# 페이지네이션 요청과 응답 (AIP-158)
GET /v1/publishers/123/books?pageSize=50&pageToken=abc

{
  "books": [ ... ],
  "nextPageToken": "def"   // 비어 있으면 마지막 페이지
}
```

## 6. 호환성과 버전 관리

점검 항목
* 이번 변경이 하위 호환을 깨는 변경인지 확인했는가 (AIP-180)
  필수 필드 추가, 필드 제거나 이름 변경, 타입 변경, 기존 메서드에 페이지네이션 추가, 검증 강화 등이 호환을 깬다.
* 안정성 단계(alpha, beta, stable)를 명확히 했는가 (AIP-181)
* 호환을 깨는 변경은 새 메이저 버전으로 분리했는가 (AIP-185)
  버전은 경로에 둔다. 예: `/v1/...`, `/v2/...`
* API 계약이 외부 소프트웨어에 불필요하게 의존하지 않는가 (AIP-182)

## 7. 마무리(Polish)

점검 항목
* 명명 규칙을 API 전체에서 일관되게 따랐는가 (AIP-190)
* 모든 메서드, 리소스, 필드에 문서 주석을 달았는가 (AIP-192)
* 오류 응답이 표준 구조와 표준 코드를 따르는가 (AIP-193)
  표준 상태 코드를 쓰고, 기계 판독용 식별자(reason, domain)와 동적 정보(metadata)를 포함한다.
* 오류 메시지가 간결하면서 해결 방향을 제시하고, 내부 구현이나 민감 정보를 노출하지 않는가 (AIP-193)
* 권한이 없으면 존재 여부와 무관하게 PERMISSION_DENIED(403)를, 권한은 있으나 없으면 NOT_FOUND(404)를 반환하는가 (AIP-193, AIP-211)
* 어떤 오류 코드가 재시도 가능한지 정의했는가 (AIP-194)
  예: 일시적 가용성 오류는 재시도 가능, 잘못된 인자는 재시도 불가.

```
# 표준 오류 응답 구조 (AIP-193)
{
  "error": {
    "code": 404,
    "status": "NOT_FOUND",
    "message": "Book 456 was not found under publisher 123.",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "BOOK_NOT_FOUND",
        "domain": "library.example.com",
        "metadata": { "publisher": "123", "book": "456" }
      }
    ]
  }
}
```

## 8. 프로토콜 버퍼 (gRPC와 proto 사용 시)

proto 기반 API에만 해당한다. REST와 JSON만 쓰는 경우에는 참고만 한다.

점검 항목
* HTTP와 gRPC 매핑을 google.api.http 애너테이션으로 정의했는가 (AIP-127)
* 공통 타입(google.rpc, google.type 등)을 재사용했는가 (AIP-213)
* API용 proto를 내부 저장용 proto와 분리했는가 (AIP-215)
* proto 파일과 디렉터리 구조가 규칙을 따르는가 (AIP-191)

## 참고 문헌

아래는 사람 리뷰어와 작성자를 위한 원문 링크다. 자동 리뷰 봇은 이 링크를 가져오지 않는다.
모든 AIP의 색인은 https://google.aip.dev/general 에 있다.

| AIP | 제목 | 링크 |
|-----|------|------|
| 1 | AIP Purpose and Guidelines | https://google.aip.dev/1 |
| 9 | Glossary | https://google.aip.dev/9 |
| 111 | Planes | https://google.aip.dev/111 |
| 121 | Resource-oriented design | https://google.aip.dev/121 |
| 122 | Resource names | https://google.aip.dev/122 |
| 124 | Resource association | https://google.aip.dev/124 |
| 126 | Enumerations | https://google.aip.dev/126 |
| 127 | HTTP and gRPC Transcoding | https://google.aip.dev/127 |
| 129 | Server-Modified Values and Defaults | https://google.aip.dev/129 |
| 130 | Methods | https://google.aip.dev/130 |
| 131 | Standard methods: Get | https://google.aip.dev/131 |
| 132 | Standard methods: List | https://google.aip.dev/132 |
| 133 | Standard methods: Create | https://google.aip.dev/133 |
| 134 | Standard methods: Update | https://google.aip.dev/134 |
| 135 | Standard methods: Delete | https://google.aip.dev/135 |
| 136 | Custom methods | https://google.aip.dev/136 |
| 140 | Field names | https://google.aip.dev/140 |
| 141 | Quantities | https://google.aip.dev/141 |
| 142 | Time and duration | https://google.aip.dev/142 |
| 143 | Standardized codes | https://google.aip.dev/143 |
| 144 | Repeated fields | https://google.aip.dev/144 |
| 145 | Ranges | https://google.aip.dev/145 |
| 146 | Generic fields | https://google.aip.dev/146 |
| 147 | Sensitive fields | https://google.aip.dev/147 |
| 148 | Standard fields | https://google.aip.dev/148 |
| 149 | Unset field values | https://google.aip.dev/149 |
| 151 | Long-running operations | https://google.aip.dev/151 |
| 152 | Jobs | https://google.aip.dev/152 |
| 153 | Import and export | https://google.aip.dev/153 |
| 154 | Resource freshness validation | https://google.aip.dev/154 |
| 155 | Request identification | https://google.aip.dev/155 |
| 156 | Singleton resources | https://google.aip.dev/156 |
| 157 | Partial responses | https://google.aip.dev/157 |
| 158 | Pagination | https://google.aip.dev/158 |
| 159 | Reading across collections | https://google.aip.dev/159 |
| 160 | Filtering | https://google.aip.dev/160 |
| 161 | Field masks | https://google.aip.dev/161 |
| 163 | Change validation | https://google.aip.dev/163 |
| 164 | Soft delete | https://google.aip.dev/164 |
| 165 | Criteria-based delete | https://google.aip.dev/165 |
| 180 | Backwards compatibility | https://google.aip.dev/180 |
| 181 | Stability levels | https://google.aip.dev/181 |
| 182 | External software dependencies | https://google.aip.dev/182 |
| 185 | API Versioning | https://google.aip.dev/185 |
| 190 | Naming conventions | https://google.aip.dev/190 |
| 191 | File and directory structure | https://google.aip.dev/191 |
| 192 | Documentation | https://google.aip.dev/192 |
| 193 | Errors | https://google.aip.dev/193 |
| 194 | Automatic retry configuration | https://google.aip.dev/194 |
| 200 | Precedent | https://google.aip.dev/200 |
| 202 | Fields | https://google.aip.dev/202 |
| 203 | Field behavior documentation | https://google.aip.dev/203 |
| 205 | Beta-blocking changes | https://google.aip.dev/205 |
| 210 | Unicode | https://google.aip.dev/210 |
| 211 | Authorization checks | https://google.aip.dev/211 |
| 213 | Common components | https://google.aip.dev/213 |
| 214 | Resource expiration | https://google.aip.dev/214 |
| 215 | API-specific protos | https://google.aip.dev/215 |
| 216 | States | https://google.aip.dev/216 |
| 217 | Unreachable resources | https://google.aip.dev/217 |
| 231 | Batch methods | https://google.aip.dev/231 |
