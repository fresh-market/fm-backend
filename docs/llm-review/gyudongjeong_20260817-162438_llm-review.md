---
검증: G-LOCAL
계정: gyudongjeong
시각: 2026-08-17T07:24:37Z
저장소: https://github.com/fresh-market/fm-backend.git
브랜치: feat/admin-login
커밋: 1f317fb6b2f99cd06b329c827f52472b29713fdb
범위: 0a65ac2aab94d90106a76096f426466afa5074c0..1f317fb6b2f99cd06b329c827f52472b29713fdb
기준 저장소:
  common: c21e1725469ee71b0483b1dfbd74b19a2c1f7119  ~/.cache/llm-verify/common (캐시)
  infra: 332bba0ae4209c5dd178a7b4bef5b5f4ed944001  ~/.cache/llm-verify/infra (캐시)
매칭 규칙: [controller, service, entity, repository, app-config, test, build]
활성 항목: 482 (backend 273, common 159, infra 50) — `--full`
---

# G-LOCAL  1f317fb  [Fix] 관리자 로그인 응답 정리 및 코드 리뷰 반영  (범위: 279a051, 1f317fb 2개 커밋)

빌드 게이트
  커버리지   통과
  정적 분석  이 실행에서는 게이트 스크립트가 SonarQube 를 부르지 않는다 (로컬은 알리기만 함, 차단은 CI 가 함)

매칭된 규칙  controller, service, entity, repository, app-config, test, build
활성 항목    482건  (backend 273, common 159, infra 50 — `--full`)

이전 판정(`gyudongjeong_20260817-154036`)에서 지적한 `API-7-02`, `EC-2-14`, `IDS-7-01` 세 건은 이번 커밋(`1f317fb`)에서 전부 고쳤다. 재판정 결과 셋 다 `OK` 다.

VIOLATION 6건

  `SEC-6-01`  로그인, 인증번호 발송 등에 시도 횟수 제한이 있는가
    기준: common qa-security-guideline.md 6장  (판정 기준표: 계정당 5회/15분 → 15분 잠금)
    src/main/java/com/freshmarket/admin/domain/service/AdminSessionService.java:54-69
    비밀번호 실패 횟수를 세거나 잠그는 코드가 없다. 코드 주석(AdminSessionService.java:23)이 "5회 실패 시 30분 잠금은 이번 범위에서 뺐다"고 스스로 밝히고 있어 인지된 누락이지만, 이번 PR로 로그인 엔드포인트가 인증 없이(`permitAll`) 열리는 시점이라 무차별 대입 공격에 그대로 노출된다.
    `admin` 테이블에 `fail_count`, `locked_until` (또는 별도 시도 기록 테이블)을 추가하는 후속 작업을 하기 전에는 이 엔드포인트를 병합하지 않거나, 최소 `SEC-6-02`(레이트 리밋)로 1차 방어막을 두는 것을 검토한다.

  `SEC-6-02`  IP 또는 계정 단위 레이트 리밋이 있는가
    기준: common qa-security-guideline.md 6장
    src/main/java/com/freshmarket/config/SecurityConfig.java:70  (`POST /v1/admin/sessions` 를 무조건 `permitAll`)
    `SEC-6-01`과 같은 원인이다. 게이트웨이나 필터 어디에도 IP/계정 단위 요청 제한이 없다.
    ALB 레벨 레이트 리밋 또는 Bucket4j 같은 애플리케이션 레벨 필터 중 하나를 이 경로에 건다.

  `SEC-6-04`  인증 실패 응답이 계정 존재 여부를 구분해서 알려 주지 않는가
    기준: common qa-security-guideline.md 6장 ("메시지를 하나로 맞춘다. 응답 시간 차이로도 드러나므로 계정이 없을 때도 해시 검증에 준하는 시간을 소비한다")
    src/main/java/com/freshmarket/admin/domain/service/AdminSessionService.java:54-69
    두 가지가 기준과 어긋난다.
    (1) 계정이 없으면 `findByLoginId` 에서 바로 `LOGIN_FAILED` 를 던지고 `passwordEncoder.matches()`(BCrypt, 의도적으로 느림)를 아예 호출하지 않는다. 존재하는 계정의 오답과 존재하지 않는 계정의 응답 시간이 달라 원문 기준이 정확히 경고하는 타이밍 오라클이 생긴다.
    (2) `ACCOUNT_INACTIVE` 는 `LOGIN_FAILED` 와 HTTP 상태(403 vs 401)와 메시지가 다르다 — 계정이 존재하지만 비활성 상태라는 사실이 응답만으로 구분된다. 코드 주석(54-61행)이 "관리자는 내부 직원이라 허용된다"고 의도적 선택임을 밝히고 있지만, 이 문서에는 그런 예외가 없고 `known-conflicts.yml` 에도 기록되어 있지 않다.
    계정 미존재 시에도 더미 해시로 `passwordEncoder.matches()` 를 호출해 시간을 맞춘다. `ACCOUNT_INACTIVE` 구분 노출을 의도적으로 유지하려면 팀 합의를 거쳐 `known-conflicts.yml` 에 `intentional` 로 기록한다.

  `SEC-5-02`  관리자 행위에 감사 로그가 남는가
    기준: common qa-security-guideline.md 5장  (감사 대상표: "관리자 로그인 — 시각, IP, 성공 여부 — 보존 1년")
    src/main/java/com/freshmarket/admin/domain/service/AdminSessionService.java (로그 호출 전무, `admin` 패키지 전체에 `log.*` 호출 없음)
    문서가 관리자 로그인을 감사 대상으로 명시했는데 성공이든 실패든 어디에도 기록하지 않는다.
    로그인 성공/실패 분기에 시각, 요청 IP, 계정, 성공 여부를 구조화 로그로 남긴다 (비밀번호 원문은 남기지 않는다, `SEC-4-02`).

  `MNT-2-03`  시간, 랜덤, 현재 사용자 같은 암묵적 의존이 주입 가능한가
    기준: common qa-maintainability-guideline.md 2장  ("현재 시각을 직접 호출하면 만료 로직을 테스트할 수 없다. `Clock` 을 주입한다.")
    src/main/java/com/freshmarket/admin/domain/service/AdminSessionService.java:79  (`LocalDateTime.now()`)
    src/main/java/com/freshmarket/common/security/JwtTokenProvider.java:30  (`Instant.now()`)
    둘 다 시각을 직접 호출한다. 문서의 예시와 정확히 같은 패턴이라 만료 경계값(리프레시 토큰 만료 직전/직후, JWT 발급 시각 등)을 고정 시각으로 테스트할 방법이 없다.
    `Clock` 을 두 클래스에 생성자로 주입하고 `Clock.now(clock)` / `LocalDateTime.now(clock)` 로 바꾼다.

  `SEC-3-03`  길이와 범위 상한이 있는가
    기준: common qa-security-guideline.md 3장  ("Bean Validation 으로 형식과 범위를 선언적으로 강제한다")
    src/main/java/com/freshmarket/admin/domain/dto/AdminSessionCreateRequest.java:11,15
    `loginId`, `password` 모두 `@NotBlank` 만 있고 상한 길이가 없다. `login_id` 컬럼은 `VARCHAR(50)` 인데 그보다 훨씬 큰 문자열을 보내도 검증 단계에서 막히지 않고 그대로 조회·BCrypt 비교까지 흘러간다.
    `loginId` 에 `@Size(max = 50)`, `password` 에 합리적인 상한(예: `@Size(max = 100)`)을 추가한다.

INSUFFICIENT_EVIDENCE 1건

  `BLD-2-03`  브랜치 보호의 필수 상태 검사에 두 게이트가 등록되어 있는가  (기준: backend build-gate-guideline.md 2장)  못 읽은 앵커: GitHub 저장소의 브랜치 보호 규칙 (로컬 파일이 아니라 저장소 설정이라 이 환경에서 읽을 수 없다)

OK 174  NOT_APPLICABLE 301

  문서별 내역 (OK / NOT_APPLICABLE / 그 외, backend 는 이전 판정 대비 재판정 결과):
    [backend]
    api-design-guideline.md              20 / 45
    base-entity-guideline.md             10 / 12
    build-gate-guideline.md               9 /  0  (INSUFFICIENT_EVIDENCE 1: BLD-2-03)
    domain-package-boundary-guideline.md 16 / 17
    effective-java-guideline.md          26 / 24
    entity-creation-guideline.md         25 /  6
    identifier-strategy-guideline.md      5 / 24
    jpa-rdb-guideline.md                  3 / 12
    unit-testing-guideline.md            19 /  1
    [common, --full]
    qa-compatibility-guideline.md         6 /  9
    qa-data-integrity-guideline.md        6 / 11
    qa-flexibility-guideline.md           5 /  7
    qa-functional-suitability-guideline.md 3 / 11
    qa-maintainability-guideline.md      11 /  1  (VIOLATION 1: MNT-2-03)
    qa-observability-guideline.md         0 / 19
    qa-performance-efficiency-guideline.md 1 / 17
    qa-reliability-guideline.md           0 / 25
    qa-security-guideline.md              9 /  9  (VIOLATION 5: SEC-3-03, SEC-5-02, SEC-6-01, SEC-6-02, SEC-6-04)
    qa-tradeoffs-guideline.md             2 /  1
    [infra, --full]
    code-guideline.md                     0 / 50  (배포/배치/이미지 저장소 등 이 PR 이 건드리지 않는 영역이라 전부 무관)

참고 (활성 항목 목록에는 없지만 눈에 띈 점, 판정 대상 아님)
  `AdminSessionResponse` 는 record 라 `accessToken`, `refreshToken` 원문을 포함한 기본 `toString()` 이 생성된다. 이 객체를 나중에 로그로 찍는 코드가 생기면 토큰이 평문으로 남는다. `SEC-5-02` 보완으로 로그인 로그를 추가할 때 이 레코드를 통째로 찍지 않도록 주의한다.
