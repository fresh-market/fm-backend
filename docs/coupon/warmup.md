# 선착순 쿠폰: 기동 워밍업

**차가운 JVM 으로 이벤트를 열지 않기 위한 장치다.** 기동 직후 인스턴스가 자기 자신에게 발급
요청을 흘려 경로를 데우고, 그것이 끝난 뒤에야 트래픽을 받는다.

구현은 `com.freshmarket.coupon.internal.warmup` 넷이다.

```
CouponWarmupRunner       ApplicationRunner.  HTTP 로 읽기 경로를 데운다
CouponWriteWarmup        배치 INSERT 를 넣었다 되돌리며 데운다
CouponWarmupProperties   coupon.warmup.*  값을 받는다
CouponWarmupConfig       값을 빈으로 올린다
```

## 1. 왜 하나

**차가운 JVM 은 요청을 처리하면서 동시에 자기를 컴파일한다.** 90초짜리 이벤트에서 JIT 이
49.7초어치를 컴파일했고, 그만큼이 요청 처리에서 빠졌다.

```
워밍업 없음        p99 4.69초
워밍업 3,000건     p99 0.288 ~ 0.939초 (표본 5)
```

2026-08-31 부하 시험이다. VU 20,000 / 재고 10,000 / t3.small 3대.

**정확성은 차가워도 지켜진다.** 초과 발급 0, 번호 유실 0, 실패율 0.00% 였다. 못 지키는 것은
p99 하나다.

**그리고 실제 이벤트는 늘 차갑게 시작한다.** `coupon-event.sh` 가 이벤트 직전에 전용 인스턴스를
띄우는 설계여서다.

측정의 자세한 것은 `fm-infra` 의 `docs/verification/선착순쿠폰_워밍업_설계와측정.md` 에 있다.
이 문서는 지금 도는 것을 적는다.

## 2. readiness 를 직접 건드리지 않는다

**러너를 `ApplicationRunner` 로 둔 것이 설계의 요점이다.** 러너는 `ApplicationReadyEvent` 앞에
돌고 스프링 부트는 그 이벤트에서 readiness 를 `ACCEPTING_TRAFFIC` 으로 올린다.

```
기동 -> ApplicationRunner (워밍업) -> ApplicationReadyEvent -> readiness UP -> ALB 가 healthy 로 본다
```

**운영 절차가 안 바뀐다.** `coupon-event.sh open` 의 "healthy 대기" 가 그대로 "warm 대기" 가
된다. 스크립트도 인프라 설정도 손대지 않는다.

### 안 끝나면 무슨 일이 나나

```
readiness 가 안 올라간다
  -> ALB 대상이 healthy 가 안 된다
  -> coupon-event.sh open 의 healthy 대기가 상한 600초를 태우고 실패한다
  -> 이벤트를 못 연다
```

**인스턴스는 죽지 않는다.** 전용 ASG 는 `health_check_type` 이 `EC2` 라(일부러 그렇게 뒀다)
readiness 실패로 교체하지 않는다. 살아서 트래픽만 못 받으므로 **자동 복구가 없고 로그가 유일한
단서다.**

그래서 `max-duration` 이 필요하다. 상한의 천장은 스크립트의 600초이지
`health_check_grace_period` 300초가 아니다. 그 값은 EC2 상태 검사에 걸린 것이라 readiness 와
무관하다.

**어떤 예외가 나도 러너는 삼키고 로그만 남긴다.** 워밍업은 최적화이지 정합성 요건이 아니다.

## 3. 읽기 경로: 소진으로 끝낸다

**워밍업은 전용 쿠폰 하나만 쓴다.** `V33__seed_coupon_warmup.sql` 이 심는 `coupon_id = 1000000`
이다.

러너는 요청을 보내기 전에 그 쿠폰의 카운터를 소진 상태로 세운다.

```
SET coupon:1000000:counter 2147483647 EX 3600
```

**그러면 요청이 순번 확보까지 갔다가 소진으로 끝난다.** 응답은 410 `SOLD_OUT_FINAL` 이고
`member_coupon` 에는 한 행도 안 쓴다.

```
데워진다   톰캣, 시큐리티 필터, JWT 검증, 쿠폰 캐시 조회, DB 회로, 순번 확보, 예외와 응답 직렬화
```

발급까지 가게 두면 `fk_mc_member` 가 워밍업용 회원 행을 요구하고 그 행이 운영 데이터에 남는다.
토큰의 주체로 쓰는 `WARMUP_MEMBER_ID = -1` 이 실재하지 않아도 되는 이유가 이것이다.

## 4. 쓰기 경로: 넣고 되돌린다

**소진으로 끝내면 큐 뒤가 통째로 차갑다.** 그 몫을 `CouponWriteWarmup` 이 맡는다.

워밍업 회원과 그 회원의 발급분을 **한 트랜잭션 안에서 넣고 통째로 롤백한다.** 외래 키 검사는
같은 트랜잭션 안을 보므로 통과하고, 되돌리면 회원 행까지 사라진다.

```
BEGIN
  INSERT INTO member        ... 500행   member_id 음수, provider 'WARMUP'
  INSERT INTO member_coupon ... 500행   MemberCouponBulkRepository.insertAll 그대로
ROLLBACK
```

```
데워진다     Hikari, Connector/J, rewriteBatchedStatements, 행 바인딩, MySQL 파싱, FK 검사
안 데워진다   큐 submit, 플러시 스레드의 배치 수집, markCommitted, future 완료
```

### 큐 뒤는 이 방식으로 못 넘는다

**커밋이 성공해야만 도는 코드이기 때문이다.**

```
롤백한다  ->  insertAll 뒤가 안 돈다   ->  completeIssued 를 못 지난다
커밋한다  ->  completeIssued 를 지난다 ->  행이 남는다
```

순환이라 발급까지 태우지 않고는 못 넘는다. 그것은 아직 안 했다.

### 세부 셋

**한 라운드가 한 트랜잭션이고 크기는 `batch-size` 다.** 이유가 둘이다. 되돌릴 행이 많을수록
서버가 언두를 되감는 시간이 길어지는데 이 프로필의 `socketTimeout` 이 300ms 라 그 시간이 길면
드라이버가 먼저 연결을 끊는다. 그리고 `long_query_time` 이 1초라 그보다 긴 문장은 슬로 쿼리
로그로 넘어가 부하 시험 분석을 더럽힌다. 라운드를 실제 플러시 한 번 크기로 자르면 둘 다
자연히 지켜진다.

**식별자는 음수 대역이다.** `member_id` 가 AUTO_INCREMENT 라 실제 회원은 늘 양수다. 음수를
명시로 넣으면 그 대역과 절대 안 겹치고 카운터도 앞으로 안 밀린다. 인스턴스마다 대역 안의 자리를
임의로 골라 세 대가 서로의 롤백을 안 기다린다.

**`issue_limit` 은 실제 수량이 아니라 라운드 크기를 쓴다.** `chk_mc_issue_seq` 가 순번이 1 이상
`issue_limit` 이하이기를 요구하는데 워밍업 쿠폰의 수량은 1 이라, 그대로 쓰면 둘째 행부터
걸린다. 이 컬럼은 발급 시점 사본일 뿐이고 `coupon` 과 외래 키로 묶여 있지 않다.

### 운영과 모드가 다르다

**실제 플러시는 트랜잭션을 안 연다.** `CouponIssueFlusher` 에 `@Transactional` 이 없고
`JdbcTemplate` 이 autocommit 커넥션으로 쓴다. 즉 운영에는 커밋하는 코드가 없고 MySQL 이 문장
끝에서 암묵적으로 커밋한다.

| | 운영 | 워밍업 |
|---|---|---|
| 행 바인딩, `rewriteBatchedStatements`, 패킷 조립, 소켓 | 같다 | 같다 |
| 커넥션 획득 | 트랜잭션 없는 경로 | 트랜잭션 바인딩 경로 |
| `setAutoCommit` 토글, 트랜잭션 begin/rollback | 안 돈다 | 돈다 |

**행당 비용의 대부분이 같은 코드라 데우려던 것은 데워진다.** 다만 운영이 안 쓰는 트랜잭션 관리
코드를 덤으로 데우고 커넥션 획득은 다른 갈래를 탄다. 똑같지는 않다.

### 한 행도 안 남는 것은 테스트가 지킨다

**데우는 것은 로그로 보이지만 "안 남는다" 는 눈으로 못 본다.** 롤백이 빠지거나 커밋으로 바뀌면
가짜 회원과 가짜 발급분이 쌓이는데 그것을 알아차릴 다른 장치가 없다.
`CouponWriteWarmupIntegrationTest` 가 그 자리를 맡는다.

## 5. 물렸던 것 셋

### 카운터를 안 세우면 데워지지 않는다

**카운터가 없으면 순번 확보 스크립트가 첫 줄에서 `-2` 를 돌려주고 끝난다.** 데우려던 경로를
하나도 안 지난다. 게다가 그 `-2` 가 재건기를 깨운다. 재건기는 "켜져 있고 수량이 있는 쿠폰의
카운터가 없으면 Redis 가 잃은 것" 으로 보기 때문에, 기동할 때마다 재건이 헛돈다.

값은 `Integer.MAX_VALUE` 다. 총량보다 크기만 하면 되므로 마이그레이션의 `total_quantity` 값에
안 묶인다. 스크립트가 `INCR` 로 넘긴 뒤 `DECR` 로 되돌리므로 값이 자라지도 않는다.

### 첫 Redis 명령이 100ms 를 못 지킨다

**2026-08-31 배포에서 세 인스턴스 모두 워밍업이 요청을 한 건도 못 보냈다.**

```
Connection initialization timed out after 100 millisecond(s)
```

이 JVM 의 첫 명령은 DNS 조회와 TCP 핸드셰이크와 Lettuce 초기화를 함께 문다. 그 전부가 운영
명령 타임아웃 100ms 안에 끝나야 하는데 차가운 JVM 은 자주 못 끝낸다.

**타임아웃을 올려서 풀지 않는다.** 100ms 는 SLO 에서 역산한 값이고(왕복 2회 + 확정 대기 800ms
= 1초), 이 문제는 정상 상태가 아니라 최초 1회다. 그래서 러너가 첫 명령을 재시도로 감싼다
(`connectRedis`, 100ms 간격 10회, 최악 1초).

**이 재시도는 워밍업만의 이야기가 아니다.** 워밍업이 없으면 그 첫 실패를 실제 사용자의 첫 요청이
문다. 러너가 미리 무는 것이 이 메서드의 값어치다.

### 영구 키는 축출을 피한다

**카운터에 TTL 을 건다(1시간).** ElastiCache 기본 축출 정책이 `volatile-lru` 라 만료가 없는 키는
축출 대상에서 아예 빠진다. 워밍업 카운터를 영구로 두면 메모리가 찼을 때 실제 발급 상태인 `seq`
와 `pending` 이 먼저 날아간다. 워밍업 쿠폰 ID 를 바꿔 배포할 때마다 옛 키가 하나씩 쌓이는 문제도
같이 걸린다.

## 6. 설정

`application-coupon.yml` 의 `coupon.warmup` 이다. `coupon` 프로필에서만 이 빈들이 뜬다.

| 키 | 값 | 근거 |
|---|---|---|
| `enabled` | `true` | 로컬과 시험에서는 끈다 |
| `coupon-id` | `1000000` | V33 이 심는 전용 쿠폰. 999999 는 이 저장소가 "없는 것" 으로 쓰는 번호라 피했다 |
| `requests` | `3000` | 200 으로는 모자랐다 (최대 응답 1.72초) |
| `concurrency` | `20` | 순차로 보내면 실제 이벤트의 동시성을 못 흉내 낸다 |
| `max-duration` | `60s` | 3,000건이 약 30초다. 천장은 스크립트의 600초다 |
| `write-rows` | `5000` | 실제 이벤트가 만 행이라 그 절반. 논증으로 잡은 시작값이다 |
| `write-timeout` | `20s` | 한 라운드의 트랜잭션 상한. 정상이면 수백 밀리초에 끝난다 |

**값은 AWS 회차에서 정한다.** 로컬에서 정하지 않는 이유는 그쪽 MySQL 이 매 회차 새 컨테이너라
개선의 일부가 JIT 이 아니라 버퍼 풀이 데워진 몫이고, k6 와 JVM 과 DB 가 같은 CPU 를 다투기
때문이다. 그렇게 고른 값을 운영에 박으면 근거 없는 값이 근거 있어 보인다.

## 7. 무엇을 보고 확인하나

기동 로그에 이것들이 남는다. 앞의 셋 중 하나는 반드시 남는다.

```
COUPON_WARMUP_DONE              sent / ok / writeRows / elapsedMs
COUPON_WARMUP_TIMEOUT           max-duration 에서 끊었다. sent 만 남는다
COUPON_WARMUP_FAILED            워밍업이 통째로 실패했다. 앱은 정상 기동한다
COUPON_WARMUP_REDIS_CONNECTED   첫 커넥션에 재시도가 필요했다. attempts 가 몇 회였는지 남는다
COUPON_WRITE_WARMUP_DONE        쓰기 경로를 데웠다. rows / chunk / elapsedMs
COUPON_WRITE_WARMUP_FAILED      한 라운드가 실패해 거기서 멈췄다. 앞 라운드는 이미 지났다
COUPON_WRITE_WARMUP_SKIPPED     기본 등급이나 워밍업 쿠폰이 없다. 마이그레이션을 확인한다
```

**`FAILED` 가 나도 앱은 뜬다.** 다만 그 인스턴스는 차가운 채로 트래픽을 받으므로, 이벤트를 열기
전이라면 그 인스턴스를 교체하는 편이 낫다. **회차마다 세 인스턴스 모두를 확인해야 한다.**
2026-08-31 에 셋 다 조용히 실패했는데 healthy 는 통과했다.

**부하 시험 지표를 읽을 때 주의한다.** 워밍업이 보낸 3,000건이 `coupon_issue_results_total` 의
`sold-out-final` 에 그대로 더해진다. `sold-out` 이 아니다. 회차별 수치는 절대값이 아니라 회차
시작 전후의 차이로 읽는다.

## 8. 이 문서가 다루지 않는 것

- 워밍업이 데우는 발급 경로 자체의 설계는 [coupon.md](coupon.md) 에 있다.
- 그날의 측정과 그때의 설계 결정은 `fm-infra` 의
  `docs/verification/선착순쿠폰_워밍업_설계와측정.md` 에 있다.
- Redis 가 키를 잃었을 때의 복구는 [redis-promotion-rebuild.md](redis-promotion-rebuild.md) 에 있다.
- 가상 스레드를 켜고 끈 비교는 [virtual-thread-measurement.md](virtual-thread-measurement.md) 에 있다.
