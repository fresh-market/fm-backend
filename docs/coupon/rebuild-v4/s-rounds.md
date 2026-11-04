# `rebuild-v4` 장애 4종 회차 (S, T)

**판정 지표가 실제로 `http_req_duration` 으로 돌아간 첫 측정이다.**

```
AWS 실측     20,000 VU / ramp 60초 / hold 30초 / 재고 10,000장 / 전용 3대
주입         k6 를 먼저 띄우고 24~25초 뒤에 걸었다
원자료       s-rounds/<회차>/
대시보드     없다.  뽑기 전에 인프라를 내렸다
```

**[`rounds.md`](rounds.md) 와 섞지 않는다.** 그 문서의 p99 는 `coupon_settled_duration` 이다.
임계를 `http_req_duration` 으로 옮긴 변경이 들어가 있었지만 부하 생성기가 시나리오를
`origin main` 에서만 받아 와서, 그 판은 옛 시나리오로 돌았다. 이 판은 생성기를 고친 뒤다.

**이 회차가 잰 로직은 [`design.md`](design.md) 에 있다.**

---

## 1. 결론

| 회차 | 장애 | 발급 | 미확정 번호 | 초과 발급 | `http` p99 | 실패율 | 재건 |
|---|---|---:|---:|---:|---:|---:|---:|
| T-1 | 스프링 서버 1대 비정상 종료 | **10,000** | **0** | 0 | **134.35ms** | 25.23% | 0 |
| **S-1** | **캐시 키 전부 소실** | **10,000** | **0** | 0 | **535.50ms** | **0.00%** | **3** |
| S-4 | 캐시 Multi-AZ 페일오버 | 9,642 | 5 | 0 | 973.67ms | 0.00% | 2 |
| S-3 | 스프링 서버 1대 종료 + 캐시 페일오버 | 9,723 | **0** | 0 | 569.75ms | 30.31% | 2 |
| ~~S-2~~ | ~~캐시 Multi-AZ 페일오버~~ | ~~9,998~~ | ~~2~~ | ~~0~~ | **3.18초** | 0.00% | 3 |

**4회차 다 초과 발급 0, 1인 1매, 예상 못 한 응답 0 이다.** S-2 는 임계를 깨서 아래 4장에서 따로
다룬다.

### S-1 이 이 판에서 가장 단단한 회차

**분모가 온전하고 장애가 가장 험하다.** 실패율 0% 라 모든 요청이 답을 받았고, 그래서
535.50ms 가 치우치지 않은 수다. 그 상태에서 발급 8,237건 시점에 쿠폰 키 8개를 전부 지웠는데도
재고가 전량 나가고 미확정 번호가 0 이다. 서버 3대가 각자 1번씩 재건했다.

**실패율이 0 이 아닌 회차의 p99 는 가로로 비교할 수 없다.** T-1 의 25.23% 와 S-3 의 30.31% 는
전부 게이트웨이 실패(502/504)이고, 그 요청들이 p99 모집단에서 빠진다. **빠진 것이 바로 가장
느린 요청들이라 남은 수가 좋아진다.** T-1 의 134.35ms 를 가장 좋은 값으로 읽으면 안 된다.

---

## 2. 수치의 출처

**발급과 미확정 번호와 초과 발급과 1인 1매는 DB 가 낸 수다.** 서버가 무엇이라 답했든 행이 최종
사실이라, 회차가 끝난 뒤 팀이 한 문장으로 본다.

```sql
SELECT COUNT(*), MAX(issue_seq), MAX(issue_seq) - COUNT(*) AS gap, COUNT(DISTINCT member_id)
  FROM member_coupon WHERE coupon_id = <그 회차 쿠폰>;
```

| 무엇 | 어떻게 읽나 |
|---|---|
| 발급 | `COUNT(*)`. 행 수다 |
| 미확정 번호 | `MAX(issue_seq) - COUNT(*)`. 번호는 나갔는데 행이 없는 것 |
| 초과 발급 | `COUNT(*)` 가 재고 10,000 을 넘는지 |
| 1인 1매 | `COUNT(DISTINCT member_id)` 가 `COUNT(*)` 와 같은지 |

**k6 의 `coupon_issued` 와 DB 행 수는 다르다.** k6 쪽은 `alreadyIssued: false` 로 답한 200 만
세고, 재시도해서 "이미 발급" 200 을 받은 회원이 빠진다. 그 회원도 쿠폰을 쥐었으니 **이 문서는 DB
기준으로 적는다.**

```
S-4   k6 coupon_issued 9,633  +  coupon_already_issued 9  =  9,642 = DB 행 수
S-3   k6 coupon_issued 9,720  +  coupon_already_issued 3  =  9,723 = DB 행 수
```

p99 와 실패율과 혼잡 수는 k6 요약에서, 재건 횟수와 구간은 `COUPON_SEQ_RAISED` 로그에서 온다.

---

## 3. 재건이 어떻게 돌았나

### S-1, 캐시 키 전부 소실

**서버 3대가 54밀리초 안에 다 찍혔고 `counter` 가 8,237 하나로 모였다.**

```
17:48:04.028  RAISED  issued=8237 dbMax=8237 mark=0 bound=8237 counter=8237 raiseMillis=148
17:48:04.061  RAISED  issued=8237 dbMax=8237 mark=0 bound=8237 counter=8237 raiseMillis=197
17:48:04.062  RAISED  issued=8237 dbMax=8237 mark=0 bound=8237 counter=8237 raiseMillis=156
17:48:04.050  FREE_NONE  counter=8237
17:48:04.068  FREE_NONE  counter=8237
```

**3대가 `mark=0` 을 들고 왔다.** 키를 지운 직후라 어느 서버의 큐에도 미확정 번호가 없었다는
뜻이고, `held=0` 인 `COUPON_SEQ_RESTORED` 가 그것을 다시 말한다. B 단계는 되돌릴 후보가 없어
`FREE_NONE` 으로 끝났다.

### S-4, 캐시 페일오버에서 1대가 포기

**서버 3대 중 2대만 재건했다.** 남은 1대는 재시도 3번을 전부 `QueryTimeoutException` 으로 잃고
그만뒀다.

```
19:12:50.372  RAISED  issued=6908 dbMax=6916 mark=6426 bound=6916 counter=6979 raiseMillis=189
19:12:50.404  FREE_WRITTEN  counter=6979 candidates=5 added=0 freeMillis=26
19:12:52.474  RAISED  issued=7825 dbMax=7834 mark=7834 bound=7834 counter=7835 raiseMillis=80
19:12:55.522  REBUILD_RETRY  attempt=1 reason=QueryTimeoutException
19:12:58.733  REBUILD_RETRY  attempt=2 reason=QueryTimeoutException
19:13:02.144  REBUILD_FAILED attempts=3
```

**이 회차에만 미확정 번호 5개가 남았다.** 다만 **그 5개가 B 단계가 본 `candidates=5` 와 같은
것이라고 말할 수 없다.** B 는 `counter=6979` 시점에 `seq` 를 보았고 최종 미확정 번호는 1번부터
9,647번까지를 놓고 센 것이다. 겹치는지 팀은 안 갈랐다.

**`added=0` 이다.** B 단계가 후보 5개를 찾고도 `free` 에 아무것도 안 넣었다. 이 판 전체에서
`added` 가 0 이 아닌 적이 없다.

### S-3, 복합 장애

**서버 1대가 죽어서 남은 2대가 각자 1번씩 했다.** 2대 다 재시도 1번을 잃고 두 번째에 성공했다.

```
18:19:38.810  REBUILD_RETRY  attempt=1 reason=QueryTimeoutException
18:19:38.872  REBUILD_RETRY  attempt=1 reason=QueryTimeoutException
18:19:41.589  RAISED  issued=9723 dbMax=9723 mark=9723 bound=9723 counter=9723 raiseMillis=111
18:19:41.667  RAISED  issued=9723 dbMax=9723 mark=9722 bound=9723 counter=9723 raiseMillis=77
```

### T-1, 서버 1대 종료

**재건이 0건이다.** 이 장애는 Redis 를 안 건드려서 재건이 뜰 이유가 없다. 죽은 서버의 큐가
응답도 못 간 채 사라졌는데도 발급 10,000 과 미확정 번호 0 인 것은, 번호를 쥔 회원이 재시도해서
같은 번호를 다시 받았기 때문이다.

---

## 4. 정지 시간을 왜 못 적나

**`raiseMillis` 는 77 ~ 197밀리초다.** 서버 1대가 자기 A 단계를 `System.nanoTime()` 으로 잰
값이라 이 수는 직접 계측이다.

**그런데 "순번 발급이 몇 초 멈췄나" 는 이 판에서 못 적는다.** 요청 쪽에 그것을 센 지표가 없고,
로그로 계산하면 **세는 법에 따라 10배 넘게 갈린다.**

```
                성공한 재건만 셀 때      실패한 시도까지 셀 때
S-1               1.20초                  1.20초     재시도가 없었다
S-3               1.19초                  3.86초
S-4               1.22초                 12.96초
```

**갈리는 까닭은 `raise()` 가 차단을 가장 먼저 걸기 때문이다.**

```java
private List<IssuedSeq> raise(long couponId, Coupon coupon) {
    long startedAt = System.nanoTime();
    long deadline = startedAt + stallBudget.toNanos();
    blockIssue(couponId);          // DB 를 읽기 전이다
    ...
```

DB 읽기에서 실패해 되돌아간 시도도 **이미 차단을 걸어 놓았다.** S-4 의 포기한 서버는 그렇게
3번 더 걸었고, 마지막 시도가 19:13:02 이다. 첫 서버가 A 를 시작한 19:12:50 부터 치면 13초에
걸쳐 차단이 들어갔다.

**그 13초가 내내 막혀 있었다는 뜻은 아니다.** 차단은 1초 뒤에 저절로 풀리고 재시도 사이가 그보다
멀어서, 중간에 풀렸다 다시 걸렸을 것이다. **몇 번 걸렸고 그 사이가 얼마였는지는 이 로그로 못
가린다.** 시도가 끝날 때(`REBUILD_RETRY`)만 찍고 시작할 때를 안 찍기 때문이다.

**그래서 이 문서는 정지 시간을 수로 적지 않는다.** [`rounds.md`](rounds.md) 3장의
1.23 ~ 1.88초는 그 판(`settled`)의 값이고 이 판의 값이 아니다.

---

## 5. S-2 를 뺀 근거

**캐시 페일오버를 2번 돌렸고 첫 회차인 S-2 가 임계를 깼다.**

```
S-2   ✗ 'p(99)<1000' p(99)=3.18초    max 4.24초   혼잡 54,189
S-4   ✓ 'p(99)<1000' p(99)=973.67ms  max 1.39초   혼잡 57,227
```

**차이는 앞 회차와의 간격이다.**

```
S-1 끝  17:49:17   ->  S-2 시작  17:55:20      6분 3초
S-3 끝  18:20:17   ->  S-4 시작  19:11:47     51분 30초
```

`coupon-load-scenarios.md` 가 페일오버 회차에 15분을 권한다. **S-2 는 그 절반도 안 기다렸다.**
앞 회차가 페일오버시킨 캐시가 아직 제자리로 안 돌아온 상태에서 또 페일오버를 걸었을 수 있다.

**S-2 의 정합성은 다른 회차와 같다.** 발급 9,998 / 미확정 번호 2 / 초과 발급 0 이고 서버 3대가
각자 재건했다. **뺀 것은 지연 수치뿐이고 수치 자체는 위 표에 남겼다.**

**이것은 추정이고 확인이 아니다.** 같은 간격으로 다시 돌려 재현해 보지 않았다.

---

## 6. 안 한 것

```
반복 측정      장애마다 1회차뿐이다.  S-2 를 빼면 페일오버만 2회다
대시보드       뽑기 전에 인프라를 내렸다.  Prometheus 볼륨이 destroy 와 함께 사라졌다
정지 시간      요청 쪽에 센 지표가 없다 (4장)
added=0        B 단계가 후보를 찾고도 안 넣는 까닭을 안 갈랐다
미확정 번호 5   S-4 의 5개가 B 가 본 candidates=5 와 같은 것인지 안 갈랐다
시계           서버 3대의 시계가 맞는다는 것을 확인한 기록이 없다
```

**대시보드가 없는 것이 이 판의 가장 큰 구멍이다.** `coupon-load-scenarios.md` 10장이 2026-09-30
회차에서 같은 일을 겪고 경고를 적어 두었는데 이 판에서 또 그랬다. 숫자와 로그는 남았고 그림만
없다.

---

## 관련 문서

| 문서 | 무엇 |
|---|---|
| [design.md](design.md) | 이 회차가 잰 재건 로직 |
| [rounds.md](rounds.md) | 앞 판. p99 가 `settled` 다 |
| [../coupon-load-scenarios.md](../coupon-load-scenarios.md) | 회차를 어떻게 짜고 무엇을 남기나 |
| [../coupon-reclaim-after.md](../coupon-reclaim-after.md) | 번호 회수 기준 시간 |
