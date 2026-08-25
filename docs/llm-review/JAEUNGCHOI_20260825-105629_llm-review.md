---
검증: G-LOCAL
계정: JAEUNGCHOI
시각: 2026-08-25T01:56:29Z
저장소: https://github.com/fresh-market/fm-backend.git
브랜치: feat/lot-disposal
커밋: 04699893159218f7646d8568bb1a87b8610b38b6
범위: f3e70ba0211fec6f567cbf8c8dae4b1fc155494c..04699893159218f7646d8568bb1a87b8610b38b6
기준 저장소:
  common: c21e1725469ee71b0483b1dfbd74b19a2c1f7119  /c/Users/JAEUNGCHOI/.cache/llm-verify/common
  infra: 332bba0ae4209c5dd178a7b4bef5b5f4ed944001  /c/Users/JAEUNGCHOI/.cache/llm-verify/infra
매칭 규칙: [controller, service, entity, repository, migration, test]
활성 항목: 265 (backend 265) — --full 아님, common/infra 118+30건은 판정하지 않음
---

# G-LOCAL 0469989 판정

커밋 14개(f3e70ba..0469989), 변경 파일 25개. 실질 변경은 두 갈래다.

1. 이번 세션에서 고친 것: AdminLotService(CMP-4-04), OptionAvailabilitySyncRetryService/Failure(청크 처리, REL-2-07 exhausted), ProductOptionAvailabilityService(DI-2-01)와 관련 테스트, V16 마이그레이션.
2. `develop` 머지로 들어온 것: 카카오 unlink 실패 건 운영자 해소 기능 일체(컨트롤러/서비스/DTO/레포지토리/엔티티/V15 마이그레이션) — 이 PR이 직접 작성한 코드는 아니지만 diff에 포함되어 판정 대상이다.

## 빌드 게이트
통과 (커버리지, 신규 Blocker 0건 — 이미 로컬에서 ./gradlew check로 확인함)

## 매칭된 규칙
controller, service, entity, repository, migration, test

## 활성 항목 265건 (backend, --full 아님)

## VIOLATION 7건

### API-3-02 리소스가 최소한 Get을 지원하는가
기준: backend api-design-guideline.md 3장
파일: `src/main/java/com/freshmarket/member/domain/controller/KakaoUnlinkFailureAdminController.java`
`kakao-unlink-failures` 리소스에 목록 조회(GET, List)와 커스텀 액션(resolve)만 있고 단건 조회(GET /{failureId})가 없다.
고치기: 단건 조회가 실제로 필요 없다면(운영 화면이 목록에서 바로 처리하는 흐름이라면) 이 항목을 NOT_APPLICABLE로 재분류할 근거를 PR에 남겨라. 필요하면 GET /{failureId}를 추가하라.

### API-3-08 커스텀 메서드는 표준 HTTP 동사에 콜론 표기로 동사를 붙였는가
기준: backend api-design-guideline.md 3장
파일: `src/main/java/com/freshmarket/member/domain/controller/KakaoUnlinkFailureAdminController.java:24`
`PATCH /v1/admin/kakao-unlink-failures/{failureId}/resolve`는 상태 전이를 일으키는 커스텀 메서드인데 `/resolve` 서브경로 + PATCH로 만들었다. 같은 저장소의 `AdminLotController`가 이미 `POST /v1/admin/lots/{lotId}:dispose` 형태(콜론 표기, 커스텀 액션은 POST)로 이 규칙을 따르고 있어 이 컨트롤러만 다른 관례를 쓴다.
고치기: `POST /v1/admin/kakao-unlink-failures/{failureId}:resolve`로 바꿔라.

### DPB-4-06 관리자 전용 컨트롤러, 서비스, DTO의 이름이 Admin으로 시작하는가
기준: backend domain-package-boundary-guideline.md 4장
파일: `src/main/java/com/freshmarket/member/domain/controller/KakaoUnlinkFailureAdminController.java`, `src/main/java/com/freshmarket/member/domain/service/KakaoUnlinkFailureResolutionService.java`
클래스 이름이 `KakaoUnlinkFailureAdminController`/`KakaoUnlinkFailureResolutionService`로, `Admin`이 이름 중간에 있거나 아예 없다. 같은 저장소의 `AdminLotController`, `AdminProductController`, `AdminCategoryController`, `AdminLotService`, `AdminProductService`는 전부 `Admin`으로 시작한다.
고치기: `AdminKakaoUnlinkFailureController`, `AdminKakaoUnlinkFailureResolutionService`(또는 팀 명명 관례에 맞는 동등한 이름)로 변경하라.

### EC-2-07 필수 필드 검증이 private 생성자에 있는가
기준: backend entity-creation-guideline.md 2장
파일: `src/main/java/com/freshmarket/product/domain/entity/OptionAvailabilitySyncFailure.java:45`, `src/main/java/com/freshmarket/member/domain/entity/KakaoUnlinkFailure.java:42`
두 엔티티의 private 생성자 모두 필수 필드(productOptionId/occurredAt, memberId/kakaoUserId)의 null 검증이 없다. (참고: 같은 엔티티의 다른 브랜치 버전에는 `validateProductOptionId`/`validateOccurredAt` 검증이 이미 추가돼 있어, 이 브랜치가 그 수정을 아직 못 받은 상태다.)
고치기: private 생성자 시작부에서 필수 필드의 null 검증을 추가하라(IllegalArgumentException).

### EC-2-14 그 메서드가 전이 전제 조건을 검사하는가
기준: backend entity-creation-guideline.md 2장
파일: `src/main/java/com/freshmarket/product/domain/entity/OptionAvailabilitySyncFailure.java:82`(markExhausted), `src/main/java/com/freshmarket/member/domain/entity/KakaoUnlinkFailure.java:60`(resolve)
같은 도메인의 `ProductImage.confirm()`/`markAsMain()`은 전이 전 상태를 메서드 스스로 검사해 위반이면 예외를 던지는데, `markExhausted()`와 `resolve()`는 아무 전제조건도 검사하지 않고 무조건 플래그를 세운다. 현재는 호출부(`OptionAvailabilitySyncOutcomeService`, `KakaoUnlinkFailureResolutionService`)가 호출 전에 조건을 확인해 우연히 안전하지만, 엔티티 자신은 두 번 호출되거나 조건이 안 맞을 때도 막지 못한다.
고치기: `markExhausted()`는 이미 `exhausted`면, `resolve()`는 `!shouldGiveUp()`이면 `IllegalStateException`을 던지도록 자체 검사를 추가하라.

### IDS-2-02 / IDS-7-01 API가 단독으로 지목하는데 public_id를 누락 / 응답 DTO나 API 경로에 내부 Long id가 없는가
기준: backend identifier-strategy-guideline.md 2장, 7장
파일: `src/main/java/com/freshmarket/member/domain/controller/KakaoUnlinkFailureAdminController.java:24,36`, `src/main/java/com/freshmarket/member/domain/dto/KakaoUnlinkFailureResponse.java:8`
새 API가 경로(`{failureId}`)와 응답(`KakaoUnlinkFailureResponse.failureId`) 양쪽에 내부 `Long` PK를 그대로 노출한다. (참고: 이 저장소 전체가 아직 `public_id` 인프라를 도입하지 않은 상태라 `AdminLotController` 등 기존 API도 같은 상태다 — 이 PR만의 새로운 문제라기보다 전사적으로 유예된 기존 결정의 연장선이다. 다만 새 API가 그 결정을 재확인 없이 그대로 답습했다는 점에서 항목 자체는 위반으로 남는다.)
고치기: 별도 판단 불필요 — 이미 있는 "외부 식별자 도입 유예" 결정을 그대로 따른다는 합의만 남기면 된다.

## OK 84건

api-design-guideline.md: API-2-01,02,03,08 / API-3-04 / API-4-01,02,03,07,12 / API-5-01,02,15,16 / API-7-01,02,03,04 (18)
base-entity-guideline.md: BE-1-01,02,05 / BE-2-01,02 / BE-3-01,02,03,04 (9)
domain-package-boundary-guideline.md: DPB-1-01,02 / DPB-2-01,02,03,05 / DPB-4-02,03,07,08,09,10 / DPB-6-01 (13)
effective-java-guideline.md: EJ-1-01 / EJ-3-01 / EJ-6-02 / EJ-7-01 / EJ-8-07 / EJ-9-01,02,04,05,06 (10)
entity-creation-guideline.md: EC-1-01,02 / EC-2-01,02,03,04,05,06,09,12,13 (11)
identifier-strategy-guideline.md: IDS-9-01 (1)
jpa-rdb-guideline.md: JPA-1-01,02 / JPA-4-01,02 / JPA-5-01 (5)
unit-testing-guideline.md: UT-1-01,02,03,04 / UT-2-01,02,03 / UT-3-01,02,03,04 / UT-4-01,02,03 / UT-6-01,02,03 (17)

## NOT_APPLICABLE 174건

api-design-guideline.md: API-2-04,05,06,07 / API-3-01,03,05,06,07,09,10 / API-4-04,05,06,08,09,10,11,13,14,15 / API-5-03,04,05,06,07,08,09,10,11,12,13,14 / API-6-01,02,03,04 / API-7-05,06 / API-8-01,02,03,04 (43)
base-entity-guideline.md: BE-1-03,04,06,07 / BE-2-03,04 / BE-4-01,02,03,04 / BE-5-01,02,03 (13)
domain-package-boundary-guideline.md: DPB-1-03,04 / DPB-2-04 / DPB-3-01,02,03,04,05,06 / DPB-4-01,04,05 / DPB-5-01,02,03 / DPB-6-02,03,04,05 / DPB-7-01 (20)
effective-java-guideline.md: EJ-1-02,03,04,05,06 / EJ-2-01,02,03 / EJ-3-02,03,04,05,06 / EJ-4-01,02,03,04 / EJ-5-01,02,03 / EJ-6-01,03,04 / EJ-7-02,03,04 / EJ-8-01,02,03,04,05,06 / EJ-9-03 / EJ-10-01,02,03,04,05 / EJ-11-01,02 (40)
entity-creation-guideline.md: EC-1-03 / EC-2-08,10,11 / EC-3-01,02,03,04,05,06,07,08,09 / EC-4-01,02,03,04,05 (18)
identifier-strategy-guideline.md: IDS-1-01,02 / IDS-2-01,03,04,05 / IDS-3-01,02,03 / IDS-4-01,02,03,04,05 / IDS-5-01,02,03,04 / IDS-6-01,02,03 / IDS-7-02,03,04 / IDS-8-01 / IDS-10-01 (26)
jpa-rdb-guideline.md: JPA-1-03 / JPA-2-01,02,03 / JPA-3-01,02 / JPA-4-03,04,05 / JPA-5-02 (10)
unit-testing-guideline.md: UT-5-01,02,03,04 (4)

## INSUFFICIENT_EVIDENCE 0건
## CONFLICTING_BASELINE 0건

---

verdict         건수
VIOLATION       7
OK              84
NOT_APPLICABLE  174
INSUFFICIENT_EVIDENCE  0
CONFLICTING_BASELINE   0
합계            265
