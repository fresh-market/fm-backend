---
검증: G-LOCAL
계정: JAEUNGCHOI
시각: 2026-08-20T11:36:18+09:00
저장소: https://github.com/fresh-market/fm-backend.git
브랜치: feat/admin-product-register
커밋: cef2c3020d67c9911b0726fa8aadcfc1e721db2f
범위: 5618e09e774ed23b2b7f547a31da5ccbd3fb3ba9..cef2c3020d67c9911b0726fa8aadcfc1e721db2f
기준 저장소:
  common: c21e1725469ee71b0483b1dfbd74b19a2c1f7119  C:\Users\JAEUNGCHOI\.cache\llm-verify\common  (캐시)
  infra: 332bba0ae4209c5dd178a7b4bef5b5f4ed944001  C:\Users\JAEUNGCHOI\.cache\llm-verify\infra  (캐시)
매칭 규칙: [controller, service, entity, repository, test, build]
활성 항목: 275 (backend 275, common 0, infra 0)  — --full 미사용, backend 항목만 판정
---

# G-LOCAL cef2c30 [Feat] 상품 등록 시 같은 상품 안 옵션 이름 중복을 오류로 구분한다

## 빌드 게이트
커버리지   통과 (`jacocoTestCoverageVerification`, `*.domain.service.*` 메서드 100%)
정적 분석  통과 (`./gradlew check` 전체 성공 — 단위 테스트, 통합 테스트, ArchUnit, jacoco 게이트 포함)

## 매칭된 규칙
controller, service, entity, repository, test, build

## 활성 항목
275건 (backend 275)  — 범위: 16개 커밋 누적 diff, 변경 파일 35개

## VIOLATION 2건

**`IDS-7-01`  응답 DTO나 API 경로에 내부 `Long id`가 없는가**
  기준: backend `identifier-strategy-guideline.md` 7장
  파일:
    - `src/main/java/com/freshmarket/product/domain/dto/AdminProductResponse.java:9` (`Long productId`)
    - `src/main/java/com/freshmarket/product/domain/dto/AdminProductOptionResponse.java:8` (`Long productOptionId`)
    - `src/main/java/com/freshmarket/product/domain/dto/ProductListItem.java:7,9` (`Long productId`, `CategorySummary.categoryId`)
    - `src/main/java/com/freshmarket/product/domain/dto/ProductWithMinPrice.java:8,10` (`Long productId`, `categoryId`)
    - `src/main/java/com/freshmarket/product/domain/dto/AdminProductCreateRequest.java:16-17` (`categoryId`, `supplierId`를 클라이언트가 그대로 주고받음)
  무엇이 문제인가: 이번 diff가 새로 만든 상품/카테고리 관련 응답 DTO 전부가 DB의 내부 `Long` PK(`product_id`, `product_option_id`, `category_id`)를 그대로 필드명·타입까지 노출한다. `BasePublic*` 계열 베이스 클래스가 저장소 전체에 아직 존재하지 않고(`grep`으로 확인, 0건), `public_id`를 쓰는 엔티티도 하나도 없다. 이 값이 한 번 API로 나가면 클라이언트가 정렬 가능한 순번이라는 것과 대략적 규모(가입자 수 등)를 추측할 수 있고, 나중에 `public_id` 체계를 도입해도 이미 노출된 `Long id`를 되돌릴 수 없다.
  어떻게 고치는가: 이번 diff 하나로 해결할 사안은 아니고, 저장소 차원에서 `BasePublic*` 계열을 설계해 도입할지부터 결정해야 한다. `V1__init_schema.sql` 상단 주석에 이미 "외부 노출 식별자(public_id)는 이 스키마에 넣지 않는다. 추후 고려한다"라고 스스로 적어둔 것과 정확히 같은 사안이라, 팀이 이미 인지하고 미룬 상태로 보인다. G-LOCAL은 차단하지 않으므로, 이 필드들이 계속 쌓이고 있다는 사실만 기록해 둔다.

**`UT-5-01`  데이터베이스처럼 우리가 관리하고 외부에 노출되지 않는 의존성은 실제로 사용해 통합 테스트하는가**
  기준: backend `unit-testing-guideline.md` 5장
  파일: `src/main/java/com/freshmarket/product/domain/service/AdminProductService.java` (전체), `src/main/java/com/freshmarket/product/domain/controller/AdminProductController.java` (전체)
  무엇이 문제인가: 이번 브랜치가 새로 만든 "상품 등록"(`POST /v1/admin/products`) 경로는 단위 테스트(Mockito)만 있고 실제 DB·MockMvc를 쓰는 통합 테스트가 없다. 반면 같은 diff 안의 팀원 작업(상품 목록 조회, `GET /v1/products`)은 `ProductApiIntegrationTest.java`로 통합 테스트가 있다. `AdminProductService`가 FK 위반 메시지 문자열(`fk_product_supplier`, `uk_option_product_name` 등)을 파싱해 오류를 구분하는 로직은 실제 MySQL 제약이 그 문자열을 정말 그렇게 내는지 확인해야 신뢰할 수 있는데, 지금은 그 문자열을 테스트 코드가 손으로 지어서 준 것뿐이라 실제 DB 동작과 다를 위험이 검증되지 않는다.
  어떻게 고치는가: `product-test-supplier.sql` 같은 시드 데이터를 재사용해 `AdminProductApiIntegrationTest`를 추가하고, 정상 등록·중복 옵션명 위반·존재하지 않는 카테고리/공급처 케이스에서 실제 DB가 던지는 예외 메시지가 `AdminProductService`의 문자열 매칭과 실제로 맞아떨어지는지 최소 1~2건 확인한다.
  참고: 이 세션 대화에서 이미 "시간 제약상 Repository 통합 테스트는 당분간 스킵"이라는 팀 방침이 사전에 합의된 상태다(메모리 `feedback_skip_integration_tests`). 이미 알고 받아들인 부채이지 이번에 새로 생긴 실수는 아니다. G-LOCAL은 그런 상태도 그대로 보여주는 도구라 일단 그대로 낸다.

## INSUFFICIENT_EVIDENCE 3건
- `API-6-02` (5장, 기준: backend `api-design-guideline.md`)  안정성 단계(alpha, beta, stable)를 명확히 했는가  — 이 API의 안정성 단계를 밝힌 문서를 diff 범위에서 찾지 못함
- `API-7-06` (7장, 기준: backend `api-design-guideline.md`)  어떤 오류 코드가 재시도 가능한지 정의했는가  — `ProductErrorCode`에 재시도 가능 여부 표시가 없고, 별도 문서도 diff에 없음
- `BLD-2-03` (2장, 기준: backend `build-gate-guideline.md`)  브랜치 보호의 필수 상태 검사에 `G-BUILD`가 등록되어 있는가  — GitHub 저장소 설정이라 로컬 checkout에서는 확인 불가

## OK 147  NOT_APPLICABLE 123

문서별 내역(OK / NOT_APPLICABLE):
- api-design-guideline.md: 27 / 34
- base-entity-guideline.md: 10 / 12
- build-gate-guideline.md: 9 / 0
- domain-package-boundary-guideline.md: 19 / 15
- effective-java-guideline.md: 28 / 22
- entity-creation-guideline.md: 21 / 10
- identifier-strategy-guideline.md: 2 / 26
- jpa-rdb-guideline.md: 13 / 2
- unit-testing-guideline.md: 18 / 3

주요 OK 근거(발췌):
- `JPA-1-02`, `JPA-3-01/3-02`: `Product`/`ProductOption`이 연관관계 매핑 대신 `Long` FK 컬럼을 쓰고, `AdminProductService.register()`가 상품과 옵션을 각각 별도 `save()`로 명시적으로 나눠 저장한다(cascade 없음).
- `BLD-1-01~1-07`: `build.gradle`의 `jacocoTestCoverageVerification`이 `*.domain.service.*`를 `CLASS`/`METHOD`/`1.00`으로 정확히 좁혔고, `test.exec`만 읽으며, `check`가 `integrationTest`와 `jacocoTestCoverageVerification`에 의존하고, `coverageDataCheck`로 `.exec` 부재 상태를 막는다. 전부 확인함.
- `DPB-4-06~4-10`, `DPB-6-01`: `AdminProductController`/`AdminProductService`가 `Admin` 접두어와 `/v1/admin/` 경로를 일치시켰고, `AdminProductController`가 package-private이며, `ArchitectureTest`가 계층 이름 접미어와 계층 방향을 빌드에서 강제한다(`./gradlew check` 통과로 확인).
- `EC-2-01~2-09`: `Product`/`ProductOption`의 기본 생성자가 `PROTECTED`이고, 필수 필드 검증이 private 생성자에 모여 있으며, `register()` 팩터리가 필수값을 컴파일 시점에 강제한다.

주요 NOT_APPLICABLE 근거(발췌):
- `API-8-*`(proto/gRPC), `EJ-11-*`(직렬화), `EJ-10-*`(스레드 동기화): 이 저장소는 REST/JPA만 쓰고 이번 diff에 해당 기술이 전혀 없음.
- `IDS-2-*~6-*`(public_id 도입 절차): 이번 diff가 `public_id`를 아예 도입하지 않아 "도입한다면"을 전제하는 항목들은 성립하지 않음 (다만 그 부재 자체는 `IDS-7-01` VIOLATION으로 이미 반영함).
- `API-3-02, 3-06, 3-07`: 이슈 #13 스코프가 등록(`POST`)만이고 조회/수정/삭제는 명시적으로 다음 체크포인트로 미뤄둔 상태라, 지금 없다고 위반으로 보지 않음.

## 알려진 모순
`known-conflicts.yml` 확인함. `affects`에 이번 diff의 backend 항목(`API-*`, `BE-*`, `BLD-*`, `DPB-*`, `EJ-*`, `EC-*`, `IDS-*`, `JPA-*`, `UT-*`)과 겹치는 `unresolved`/`intentional` 항목 없음.

## 범위 밖(common) 관찰 — 참고용, 이번 판정에 포함하지 않음
`--full`을 쓰지 않아 정식 판정 대상은 아니지만, `SecurityConfig.java`를 anchor로 읽던 중 발견해서 적어 둔다. `authorizeHttpRequests`가 `/v1/admin/**` 경로에 대해 `anyRequest().authenticated()`만 적용하고, 저장소 전체에 `@PreAuthorize`/`hasRole`/`hasAuthority` 등 역할 기반 접근 제어가 하나도 없다(`grep` 0건). 즉 로그인한 일반 회원이면 누구나 `POST /v1/admin/products`를 호출할 수 있다. `qa-security-guideline.md`(common, `SEC-1-*`) 영역이라 이번 backend 전용 판정에는 안 넣었지만, `--full`로 다시 돌리거나 별도로 확인해 볼 가치가 있어 보인다.
