---
검증: G-LOCAL
계정: gyudongjeong
시각: 2026-08-17T06:40:43Z
저장소: https://github.com/fresh-market/fm-backend.git
브랜치: feat/admin-login
커밋: 279a051902648bf7e901b3e02f4e1e72f1de3758
범위: 0a65ac2aab94d90106a76096f426466afa5074c0..279a051902648bf7e901b3e02f4e1e72f1de3758
기준 저장소:
  common: c21e1725469ee71b0483b1dfbd74b19a2c1f7119  ~/.cache/llm-verify/common (캐시, 이번 실행에서 새로 clone)
  infra: 332bba0ae4209c5dd178a7b4bef5b5f4ed944001  ~/.cache/llm-verify/infra (캐시, 이번 실행에서 새로 clone)
매칭 규칙: [controller, service, entity, repository, app-config, test, build]
활성 항목: 273 (backend 273, common 0, infra 0)
---

# G-LOCAL  279a051  [Feat] 관리자 로그인 기능을 추가한다

빌드 게이트
  커버리지   통과
  정적 분석  이 실행에서는 게이트 스크립트가 SonarQube 를 부르지 않는다 (로컬은 알리기만 함, 차단은 CI 가 함)

매칭된 규칙  controller, service, entity, repository, app-config, test, build
활성 항목    273건  (backend 273, common 0, infra 0 — `--full` 없이 실행해 backend 항목만 판정)

VIOLATION 3건

  `API-7-02`  모든 메서드, 리소스, 필드에 문서 주석을 달았는가
    기준: backend api-design-guideline.md 7장
    src/main/java/com/freshmarket/admin/domain/controller/AdminSessionController.java:146-152
    src/main/java/com/freshmarket/admin/domain/dto/AdminSessionCreateRequest.java, AdminSessionResponse.java
    프로젝트에 springdoc-openapi 가 이미 도입되어 있고(build.gradle:62, application.yml 의 swagger-ui 경로) `/swagger-ui.html` 이 인증 없이 열려 있어 API 문서 자동 생성이 프로젝트 관례인데, 새 컨트롤러 메서드와 요청/응답 DTO 어디에도 `@Operation`, `@Schema` 같은 문서 애너테이션이나 Javadoc 이 없다.
    `create()` 메서드에 `@Operation(summary = ...)`, 요청/응답 필드에 `@Schema(description = ...)` 를 추가한다.

  `EC-2-14`  그 메서드가 전이 전제 조건을 검사하는가
    기준: backend entity-creation-guideline.md 2장 (R7)
    src/main/java/com/freshmarket/admin/domain/entity/Admin.java:321-329
    `deactivate(LocalDateTime deactivatedAt)` 가 현재 `status` 를 검사하지 않는다. 이미 `DELETED` 상태인 관리자에 다시 호출해도 막지 않고 `deletedAt` 을 새 시각으로 조용히 덮어쓴다. 최초 비활성화 시각이라는 이력이 사라진다. (`ship()` 예시처럼 상태 전이 메서드는 자신의 전제 조건을 스스로 지켜야 한다.)
    메서드 시작에서 `if (this.status == AdminStatus.DELETED) { throw new IllegalStateException(...); }` 로 전이 전제 조건을 검사한다.

  `IDS-7-01`  응답 DTO나 API 경로에 내부 `Long id`가 없는가
    기준: backend identifier-strategy-guideline.md 7장
    src/main/java/com/freshmarket/admin/domain/dto/AdminSessionResponse.java:198  (`AdminSummary.adminId`)
    `AdminSummary.adminId` 가 `admin` 테이블의 내부 PK(`Long`)를 그대로 응답에 싣는다. 이 문서 최상단의 "외부 노출 식별자는 지금 적용하지 않는다" 메모는 `public_id` 컬럼을 지금 추가할지를 미룬 것이지, 노출해도 된다는 뜻은 아니다. 이 필드를 참조하는 엔드포인트(단건 조회 등)가 프로젝트에 아직 없어 클라이언트가 실제로 필요로 하는지 근거가 없다. 한 번 응답에 실리면 클라이언트가 의존을 시작해 되돌리기 어렵다(문서의 근거 그대로).
    클라이언트가 필요로 하지 않는다면 `adminId` 필드를 뺀다. 필요하다면 2.4절의 결정 기록에 `admin` 행을 추가해 근거를 남기고, `public_id` 도입 전까지의 임시 노출임을 명시한다.

INSUFFICIENT_EVIDENCE 1건

  `BLD-2-03`  브랜치 보호의 필수 상태 검사에 두 게이트가 등록되어 있는가  (기준: backend build-gate-guideline.md 2장)  못 읽은 앵커: GitHub 저장소의 브랜치 보호 규칙 (로컬 파일이 아니라 저장소 설정이라 이 환경에서 읽을 수 없다)

OK 128  NOT_APPLICABLE 141

  문서별 내역 (OK / NOT_APPLICABLE / 그 외):
    api-design-guideline.md         17 / 45  (VIOLATION 1: API-7-02)
    base-entity-guideline.md        10 / 12
    build-gate-guideline.md          9 /  0  (INSUFFICIENT_EVIDENCE 1: BLD-2-03)
    domain-package-boundary-guideline.md  16 / 17
    effective-java-guideline.md     26 / 24
    entity-creation-guideline.md    24 /  6  (VIOLATION 1: EC-2-14)
    identifier-strategy-guideline.md 4 / 24  (VIOLATION 1: IDS-7-01)
    jpa-rdb-guideline.md             3 / 12
    unit-testing-guideline.md       19 /  1

참고 (활성 항목 목록에는 없지만 눈에 띈 점, 판정 대상 아님)
  `AdminSessionResponse` 는 record 라 Lombok 없이도 `accessToken`, `refreshToken` 원문을 포함한 기본 `toString()` 이 생성된다. 이 응답 객체를 나중에 로그로 찍는 코드가 생기면 토큰이 평문으로 로그에 남는다. 지금은 로깅하는 코드가 없어 현재 diff 에서는 위반이 아니다.
