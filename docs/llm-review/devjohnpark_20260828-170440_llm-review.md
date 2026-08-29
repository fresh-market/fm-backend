---
검증: G-LOCAL
계정: devjohnpark
시각: 2026-08-28T08:04:40Z
저장소: https://github.com/fresh-market/fm-backend.git
브랜치: feat/coupon-issue-v4
커밋: 1df00f97554c7ed100b27e87dfaf32f4584dbe05
범위: 0a743053326d212a54b3838906199646b03397b5..1df00f97554c7ed100b27e87dfaf32f4584dbe05
기준 저장소:
  common: 4ccbc2a048b5201ff369e9a70dbbaa1781fc1dcc  /Users/junseopark/Documents/dev/backend/ureca/comprehensive/common
  infra: 71460b257cce24d41872ced99613ed1d9a228901  /Users/junseopark/Documents/dev/backend/ureca/comprehensive/infra
매칭 규칙: [controller, service, entity, repository, external-client, api-contract, migration, app-config, test, build]
활성 항목: 492 (backend 275, common 163, infra 54)
---

G-LOCAL  1df00f9  [Feat] 발급 결과를 사유별로 센다

빌드 게이트
  커버리지   통과
  정적 분석  통과

매칭된 규칙  controller, service, entity, repository, external-client, api-contract, migration, app-config, test, build
활성 항목    492건  (backend 275, common 163, infra 54)

## 판정 범위에 대한 사실

범위가 334커밋 237파일이다. 브랜치를 한 번도 push 하지 않아 upstream 이 origin/main 으로
잡혔고, 그래서 이번 세션의 작업뿐 아니라 admin, cart, stock, order 등 이전 작업이 전부 들어왔다.

**coupon 도메인은 전부 판정했고 나머지는 근거를 확보하지 못했다.**
추측으로 OK 를 내지 않는다는 절차에 따라 후자는 INSUFFICIENT_EVIDENCE 로 둔다.

VIOLATION 7건

  INF-1-09  시각 기준을 애플리케이션 Clock으로 통일했는가
    기준: infra code-guideline.md 1장
    src/main/java/com/freshmarket/coupon/domain/repository/MemberCouponBulkRepository.java:47,60
    발급 행의 issued_at, created_at, updated_at 을 LocalDateTime.now() 로 직접 찍는다.
    같은 도메인의 CouponRepository 는 :now 를 앱에서 받는데 쓰기 경로만 기준이 다르다.
    ClockConfig 의 Clock 을 주입받아 LocalDateTime.now(clock) 으로 바꾼다.

  INF-3-04  쓰기 경로에 @Transactional(timeout)이 지정되어 있는가
    기준: infra code-guideline.md 3장
    src/main/java/com/freshmarket/coupon/domain/service/CouponEventService.java:62,80,117
    src/main/java/com/freshmarket/coupon/domain/repository/CouponRepository.java:40,56,74,93,140
    여덟 곳 모두 timeout 이 없다. open() 은 트랜잭션 안에서 Redis 를 기다리므로
    Redis 가 느려지면 커넥션을 잡은 채 무한정 머문다.
    timeout 을 지정한다. 값은 application-coupon.yml 의 요청 예산 2초와 함께 정한다.

  INF-1-11  배치 시작 로그에 job_name, scheduled_at, instance_id를 남기는가
    기준: infra code-guideline.md 1장
    src/main/java/com/freshmarket/common/logging/SchedulerLoggingAspect.java:39
    event=SCHEDULER_START job={} 만 남기고 scheduled_at 과 instance_id 가 없다.
    CouponEventScheduler 도 이 애스펙트에 로그를 맡기므로 같은 결함을 물려받는다.
    중복 실행을 탐지할 근거가 instance_id 뿐인데 그것이 없다.
    애스펙트에 두 필드를 추가한다.

  FLX-2-03  인메모리 캐시의 무효화가 전 인스턴스에 전파되는가
    기준: common qa-flexibility-guideline.md 2장
    src/main/java/com/freshmarket/coupon/domain/cache/CouponCache.java:117
    evict() 가 자기 JVM 의 사본만 지운다. 관리자가 이벤트를 열어도 다른 인스턴스는
    최대 TTL(5초) 동안 옛 값을 본다.
    설계가 이 발산을 TTL 로 묶기로 하고 coupon-v4.md 에 근거를 적어 두었으나,
    항목의 기준으로는 전파가 없다. 의도적 이탈이라면 known-conflicts.yml 에
    intentional 로 등록해 매번 지적되지 않게 한다.

  SEC-6-02  IP 또는 계정 단위 레이트 리밋이 있는가
    기준: common qa-security-guideline.md 6장
    src/main/java/com/freshmarket/common/auth/AuthRateLimitFilter.java:35
    LIMITED_PATHS 가 인증 경로 둘뿐이다. POST /v1/coupons/{couponId}/issues 는 대상이 아니다.
    동접 2만을 전제한 경로에 회원당 요청 상한이 없다.
    coupon-v4.md 4장이 "넣을지 안 넣을지 정하지 않았다" 로 열어 둔 항목이라 미결정 상태다.
    1인 1매는 Redis 가 판정하므로 정확성 목적은 없고 부하 감쇄만 남는다. 결정이 필요하다.

  IDS-7-01  응답 DTO나 API 경로에 내부 Long id가 없는가
    기준: backend identifier-strategy-guideline.md 7장
    src/main/java/com/freshmarket/coupon/domain/controller/CouponIssueController.java:35
    src/main/java/com/freshmarket/coupon/domain/controller/AdminCouponEventController.java:34,44,55
    경로가 @PathVariable Long couponId 로 내부 PK 를 그대로 받는다.
    coupon 테이블에 public_id 가 없어 대체할 값도 없다.
    이 저장소의 다른 컨트롤러도 같은 형태라 coupon 만의 문제가 아니다. 저장소 차원의 결정이 필요하다.

  IDS-2-02  API 가 단독으로 지목하는데 public_id를 누락하지 않았는가
    기준: backend identifier-strategy-guideline.md 2장
    src/main/resources/db/migration/V1__init_schema.sql  coupon 테이블
    POST /v1/coupons/{couponId}/issues 가 쿠폰을 단독으로 지목하는데 public_id 컬럼이 없다.
    2.2절의 두 조건에 걸리는지 판단하고 그 결과를 기록한다.

CONFLICTING_BASELINE 0건

INSUFFICIENT_EVIDENCE 다수

  범위의 237파일 중 coupon 도메인 밖(admin, cart, stock, order, member, product, common 일부)은
  이번 판정에서 근거 파일을 읽지 못했다. 해당 항목을 OK 로 내지 않는다.
  범위를 좁혀(예: ./verify.sh -n 12 --full) 다시 돌리면 이 구간이 실제로 판정된다.

OK (확인한 것만)

  BLD-1-01 ~ BLD-2-03   빌드 게이트가 통과했고 build.gradle 의 includes, counter, minimum 을 확인
  DPB-4-10              MemberCouponBulkRepository 를 Repository 접미사로 맞춤. ArchUnit 통과
  DPB-6-01              CouponIssueController, AdminCouponEventController 가 package-private
  DPB-5-02              ArchUnit 순환_의존이_없다 통과
  API-2-08              관리자 경로가 /v1/admin/coupons/ 아래에 있음
  API-3-08              커스텀 메서드가 event:open, event:close 콜론 표기
  API-7-06              소진 409(최종)와 혼잡 503(재시도 가능)을 갈라 정의
  EC-2-01 ~ EC-2-13     Coupon, MemberCoupon 이 protected 기본 생성자 + private 생성자 + 팩터리
  EC-4-02               CouponScope, DiscountType, MemberCouponStatus 가 EnumType.STRING
  EJ-5-02               CouponIssueMetrics 가 EnumMap 사용
  EJ-10-05              CouponCache, CouponWriteCircuit, CouponIssueQueue 가 스레드 안전성을 주석에 명시
  DI-3-01 ~ DI-3-06     uk_mc_coupon_member, uk_mc_coupon_seq, chk_mc_issue_seq 로 상한을 행 단위 강제
  REL-2-09              seq 회로와 write 회로 둘
  REL-2-12              한정 자원이라 대체 순번 발급기를 두지 않고 실패 처리
  REL-3-02              queue-capacity 와 request-budget 으로 큐 길이와 대기에 상한
  REL-2-02              application-coupon.yml 이 요청 예산 2s > 300ms + 1000ms 로 계층 정렬
  INF-3-02, INF-3-03    connectTimeout, socketTimeout, connection-timeout 을 전용 프로파일에 명시
  INF-3-06              maximum-pool-size 와 minimum-idle 을 5 로 동일
  INF-1-04              관리자 API 와 배치가 전부 조건부 UPDATE
  INF-1-10              CouponEventScheduler 에 @Profile("batch")
  INF-9-01              management.server.port 8081 분리, prometheus 노출
  PERF-5-03             AsyncCache 가 키 단위 단일 로딩으로 캐시 스탬피드 방어
  OBS-4-02              coupon.issue.results 로 비즈니스 지표 수집
  OBS-4-03              result 태그가 13개 고정값이라 카디널리티가 안 는다
  UT-3-01 ~ UT-5-04     given/when/then 구분, 서비스 단위 테스트, 통합 테스트 배치 규칙 통과
  MNT-4-02              주석이 왜를 설명하도록 작성
  FUN-2-04              타임아웃을 실패와 구분해 혼잡으로 답하고 큐 항목은 남김

NOT_APPLICABLE (확인한 것만)

  API-8-01 ~ API-8-04   proto 를 쓰지 않는다
  REL-2-06, REL-2-07    플러시가 재시도를 하지 않는다
  IDS-3-01 ~ IDS-5-04   coupon 도메인에 public_id 를 쓰는 엔티티가 없다
  BE-1-03, BE-1-04      coupon 도메인이 BasePublic* 를 상속하지 않는다
