---
검증: G-LOCAL
계정: devjohnpark
시각: 2026-08-28T19:17:19Z
저장소: https://github.com/fresh-market/fm-backend.git
브랜치: feat/coupon-issue-v4
커밋: c24cddfad351fea85fad91290db88bdcb261f83f
범위: 0a743053326d212a54b3838906199646b03397b5..de2949d03c8374d913eae8846e541b2f6bb94512 (판정 시점)
기준 저장소:
  common: 4ccbc2a048b5201ff369e9a70dbbaa1781fc1dcc  ../common (옆 저장소)
  infra: c72683f375607602adc80861ccd36ab66c500d3f  ../infra (옆 저장소)
매칭 규칙: [controller, service, entity, repository, external-client, api-contract, migration, app-config, test, build]
활성 항목: 492 (backend 275, common 163, infra 54)

기록 후 커밋 메시지를 한 줄로 되쓰면서 판정 대상 커밋의 SHA 가 바뀌었다.
판정 자체는 되쓰기 전 트리에 대해 한 것이고, 트리는 되쓰기 전후가 같다.
`de2949d` 는 되쓰기 전 SHA 이며 `refs/original` 에 남아 있다.

---

G-LOCAL  de2949d  [Fix] 쿠폰 관리자 API 를 관리자만 부르게 막는다

빌드 게이트
  커버리지   통과
  정적 분석  통과

매칭된 규칙  controller, service, entity, repository, external-client, api-contract, migration, app-config, test, build
활성 항목    492건  (backend 275, common 163, infra 54)

## 판정 근거의 범위

범위가 345커밋이고 자바 파일 192개가 9개 도메인에 걸쳐 있다.
이번 실행에서 실제로 읽은 것은 아래다.

```
coupon 도메인 49파일    전부 읽었다
빌드/앱 설정            build.gradle 의 jacoco 절, application.yml, application-coupon.yml
스키마                  coupon, member_coupon, member_coupon_status_history
보안 설정               SecurityConfig, ProductSecurityConfig, CouponSecurityConfig, ApiSecurityDefaults
테스트                  coupon 단위/통합, SecurityAuthorizationIntegrationTest, TestPlacementTest
저장소 전역 grep         Clock, @Transactional(timeout), changed_by, 스케줄러 로그 필드, 레이트 리밋 경로
```

order, product, stock, payment, cart, member, admin 도메인의 143파일은 읽지 않았다.
그 파일을 봐야 판정되는 항목은 `INSUFFICIENT_EVIDENCE` 로 둔다. 추측으로 `OK` 를 내지 않는다.

VIOLATION 11건

  INF-1-09  시각 기준을 애플리케이션 `Clock`으로 통일했는가
    기준: infra `code-guideline.md` 1장
    src/main/java/com/freshmarket/coupon/domain/repository/MemberCouponBulkRepository.java:49,60
    src/main/java/com/freshmarket/coupon/domain/entity/MemberCoupon.java:71
    발급 경로가 `LocalDateTime.now()` 를 직접 불러 issued_at, created_at, updated_at 을 채운다
    다른 쿠폰 코드는 전부 주입받은 Clock 을 쓰므로 이 셋만 기준이 다르다. Clock 을 주입해 맞춘다

  DI-4-02  트랜잭션 안에서 외부 API를 호출하지 않는가
    기준: common `qa-data-integrity-guideline.md` 4장
    src/main/java/com/freshmarket/coupon/domain/service/CouponEventService.java  open, closeAndSettle
    `@Transactional` 안에서 Redis 를 부른다. open 은 prepare, closeAndSettle 은 clear 다
    Redis 가 느려지면 그동안 DB 커넥션을 쥔다. Redis 호출을 트랜잭션 밖으로 빼고 상태로 잇는다

  INF-3-04  쓰기 경로에 `@Transactional(timeout)`이 지정되어 있는가
    기준: infra `code-guideline.md` 3장
    저장소 전체에 `@Transactional(timeout` 이 한 건도 없다
    쓰기 트랜잭션에 timeout 을 지정한다. 특히 만료 배치의 청크 갱신과 이벤트 종료가 대상이다

  INF-1-11  배치 시작 로그에 `job_name`, `scheduled_at`, `instance_id`를 남기는가
    기준: infra `code-guideline.md` 1장
    src/main/java/com/freshmarket/common/logging/SchedulerLoggingAspect.java:39
    `event=SCHEDULER_START job={}` 로 job 만 남긴다. scheduled_at 과 instance_id 가 없다
    중복 실행을 탐지할 근거가 없다. 두 필드를 더한다

  SEC-5-02  관리자 행위에 감사 로그가 남는가
    기준: common `qa-security-guideline.md` 5장
    src/main/java/com/freshmarket/coupon/domain/controller/AdminCouponEventController.java
    이벤트 열기, 닫기, 발급 시각 변경에 행위자가 어디에도 안 남는다
    `event:open` 은 Redis 카운터를 다시 세우는 동작이라 도는 이벤트에 걸면 선착순이 처음부터
    다시 시작한다. 그 사고가 나면 누가 눌렀는지 찾을 방법이 없다
    `@AuthenticationPrincipal` 로 받은 adminId 를 로그에 남기거나 감사 표에 기록한다

  SEC-6-02  IP 또는 계정 단위 레이트 리밋이 있는가
    기준: common `qa-security-guideline.md` 6장
    src/main/java/com/freshmarket/common/auth/AuthRateLimitFilter.java:35
    레이트 리밋이 `/v1/auth/tokens` 와 `:refresh` 두 경로에만 걸려 있다
    선착순 발급 경로에 없다. 한 계정이 같은 쿠폰을 무제한으로 두드릴 수 있고, 매번 Redis
    스크립트가 돈다. 1인 1매는 Redis 가 지키지만 부하 감쇄는 아무도 안 한다

  FLX-2-03  인메모리 캐시의 무효화가 전 인스턴스에 전파되는가
    기준: common `qa-flexibility-guideline.md` 2장
    src/main/java/com/freshmarket/coupon/domain/cache/CouponCache.java:120
    `evict` 가 이 JVM 의 사본만 지운다. 관리자가 이벤트를 열어도 다른 인스턴스는 TTL 만큼
    옛 값을 본다
    이 지연을 TTL 5초로 묶는 것이 의도된 선택이고 근거가 `coupon.md` 3장에 있다.
    다만 항목 기준으로는 전파 수단이 없는 상태다

  REL-3-02  큐 길이와 대기 시간에 상한이 있는가
    기준: common `qa-reliability-guideline.md` 3장
    src/main/resources/application-coupon.yml:72
    `queue-capacity: 2147483647` 로 사실상 무한이다
    대기 시간 상한(request-budget 2s)은 있으나 길이 상한이 없다. 앱이 급사하면 큐에 있던
    만큼을 잃으므로 이 값이 곧 손실 건수의 상한이다. 부하 시험에서 줄이기로 열어 둔 값이다

  IDS-2-02  API 가 단독으로 지목하는데 `public_id`를 누락하지 않았는가
    기준: backend `identifier-strategy-guideline.md` 2장
    src/main/resources/db/migration/V1__init_schema.sql  coupon, member_coupon
    발급 API 가 `/v1/coupons/{couponId}/issues` 로 쿠폰을 단독 지목하는데 public_id 가 없다
    스키마 주석이 "API 가 설계되지 않아 판단할 수 없다" 로 유예했으나 이제 API 가 정해졌다

  IDS-7-01  응답 DTO나 API 경로에 내부 `Long id`가 없는가
    기준: backend `identifier-strategy-guideline.md` 7장
    src/main/java/com/freshmarket/coupon/domain/controller/CouponIssueController.java
    src/main/java/com/freshmarket/coupon/domain/controller/AdminCouponEventController.java
    경로 변수가 내부 `Long couponId` 다
    한 번 노출되면 클라이언트가 의존해 되돌릴 수 없다. IDS-2-02 과 함께 결정한다

  PERF-6-02  운영과 유사한 데이터 규모에서 검증하는가
    기준: common `qa-performance-efficiency-guideline.md` 6장
    loadtest/README.md  회차 기록
    부하 시험을 부하 발생기와 앱과 DB 가 같은 16GB 한 대에 있는 상태로 돌렸다
    같은 조건 두 회차가 서로 다른 지점에서 무너져 수치를 값으로 쓸 수 없다
    부하 발생기를 다른 호스트로 분리해 다시 잰다. 그 사실이 문서에 기록되어 있다

OK 45건

  API-2-08, API-3-08, API-5-15, API-5-16, API-7-03, API-7-05, API-7-06
  BE-1-01, BE-1-05, BE-3-01
  BLD-1-01, BLD-1-02, BLD-1-03, BLD-1-04, BLD-1-05, BLD-1-07, BLD-2-01, BLD-2-02
  DI-3-01, DI-3-02, DI-3-03, DI-3-04
  DPB-1-04, DPB-4-01, DPB-4-06, DPB-4-08, DPB-4-10, DPB-6-01, DPB-6-03, DPB-7-01
  EC-4-02
  INF-1-01, INF-1-04, INF-1-05, INF-1-10, INF-3-02, INF-3-03, INF-3-06, INF-9-01, INF-9-02
  REL-2-01, REL-2-02, REL-2-03, REL-2-05, REL-2-09
  SEC-1-01, SEC-1-02, SEC-1-04, SEC-2-01
  UT-5-01, UT-5-04

NOT_APPLICABLE 4건

  API-8-01, API-8-02, API-8-03, API-8-04   proto 와 gRPC 를 쓰지 않는다

INSUFFICIENT_EVIDENCE 432건

  못 읽은 앵커가 둘로 갈린다.

  이 저장소 밖 (29건)
    INF-3-01, INF-5-01~04, INF-7-01~04, INF-9-03~05, INF-2-01~03, INF-6-04, INF-8-03
    OBS-3-06, OBS-4-04, OBS-6-01, OBS-6-02, REL-1-01, REL-1-02, REL-4-04, REL-4-05
    PERF-1-01, PERF-1-02, DI-7-02, BLD-2-03
    JVM 옵션, compose, systemd, SSM, Loki, 브랜치 보호, 알림 설정은 fm-infra 소관이다

  이번 실행에서 안 읽은 도메인 (403건)
    order, product, stock, payment, cart, member, admin 의 143파일을 봐야 판정된다
    EJ-*, MNT-*, OBS-3/4/5/7-*, FUN-*, JPA-*, CMP-*, API-4/6/7-*, EC-1/2/3-*, IDS 나머지,
    DPB 나머지, SEC 나머지, UT-1~4/6-*, PERF 나머지, DI 나머지, FLX 나머지, TRD-*, BE 나머지
