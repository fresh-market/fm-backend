# 선착순 쿠폰 부하 시험

요구사항이 정한 조건은 **재고 10,000장에 20,000명이 60초에 걸쳐 몰리는 것**이다.
이 폴더의 파일들이 그 조건을 손으로 다시 만들 수 있게 한다.

```
seed-members.sql    가상 회원 2만.  id 1000001 ~ 1020000
seed-coupon.sql     선착순 쿠폰 1장.  coupon_id 900001, 총량 10,000
seed-dummy-data.sql 정합성 검증용 유저 100만 + 발급 이력 300만.  이 시험과 별개다
mint-tokens.py      회원 토큰 2만 장 + 관리자 토큰 1장을 찍는다
issue.js            k6 시나리오
reset.sql           회차 사이에 상태를 되돌린다
```

---

## 왜 토큰을 미리 찍나

`AuthRateLimitFilter` 가 `POST /v1/auth/tokens` 를 **IP 당 분당 10회**로 막는다.
2만 명이 로그인부터 하면 그 필터에서 끝나고, 그 필터를 시험용으로 끄면 **정작 재려던 발급
경로가 아닌 것을 재게 된다.** 그래서 로그인을 건너뛰고 앱과 같은 서명 키로 토큰을 직접 만든다.

토큰의 모양이 앱과 어긋나면 2만 요청이 전부 401 로 돌아온다. 그것을 시험 당일에 알면 그 회차를
통째로 잃으므로, `LoadtestTokenMintTest` 가 이 스크립트가 찍은 토큰을 앱의 `JwtTokenProvider`
에 넣어 본다.

---

## 절차

### 1. 앱을 띄운다

```
SPRING_PROFILES_ACTIVE=local,coupon LOGGING_LEVEL_ROOT=WARN ./gradlew bootRun
```

**로그 수준을 반드시 낮춘다.** 로컬 프로파일은 root 가 DEBUG 이고 `HttpBodyLoggingFilter` 가
요청과 응답 본문을 남긴다. 그대로 재면 발급이 아니라 로깅을 재게 된다.

`coupon` 프로파일이 가상 스레드, 커넥션 풀 5, Redis 300ms 타임아웃, 회로 값을 건다.
그 값들이 이 시험의 대상이다.

### 2. 데이터를 넣는다

```
docker exec -i freshmarket-mysql mysql --default-character-set=utf8mb4 \
  -ufreshmarket -pfreshmarket freshmarket < loadtest/seed-members.sql
docker exec -i freshmarket-mysql mysql --default-character-set=utf8mb4 \
  -ufreshmarket -pfreshmarket freshmarket < loadtest/seed-coupon.sql
```

문자셋을 안 주면 확인 SELECT 의 한글 별칭에서 구문 오류가 난다.

### 3. 토큰을 찍는다

```
JWT_SECRET=<앱이 쓰는 값> python3 loadtest/mint-tokens.py
```

`loadtest/tokens.csv` 가 나오고, 관리자 토큰은 화면에 한 줄로 나온다. 그 토큰을 다음 단계에서 쓴다.

### 4. 이벤트를 연다

```
curl -X POST -H "Authorization: Bearer $ADMIN" \
  http://localhost:8080/v1/admin/coupons/900001/event:open
```

**이 호출이 Redis 카운터를 세운다.** SQL 로 `is_active` 만 켜면 카운터 없는 Redis 를 요청이
쳐서 시험이 통째로 "준비되지 않음" 으로 끝난다.

### 5. 돌린다

```
VUS=500 RAMP=30s HOLD=90s IDLE=1 k6 run loadtest/issue.js
```

```
VUS    동시 접속자 수
RAMP   여기까지 올리는 데 걸리는 시간
HOLD   램프가 끝난 뒤 유지하는 시간.  밀린 큐가 빠지는 것까지 본다
IDLE   한 VU 가 다음 사람을 집기까지 쉬는 시간
```

**토큰은 VU 번호가 아니라 반복 번호로 고른다.** 그래서 VU 를 줄여도 시도하는 사람 수는 언제나
2만이다. VU 는 동시성만 정한다.

### 6. 확인한다

k6 요약만으로는 **몇 장이 실제로 나갔는지 알 수 없다.** 예산을 넘겨 503 으로 답한 요청도 그
티켓이 큐에 남아 나중에 써지기 때문이다. DB 를 봐야 한다.

```sql
SELECT COUNT(*) AS 발급행, MAX(issue_seq) AS 최대순번,
       MAX(issue_seq) - COUNT(*) AS 구멍, COUNT(DISTINCT member_id) AS 회원수
  FROM member_coupon WHERE coupon_id = 900001;
```

```
발급행 = 10000   재고만큼 나갔다
구멍 = 0         번호만 태우고 사라진 요청이 없다
회원수 = 발급행   1인 1매가 지켜졌다
```

앱이 낸 갈래별 건수는 관리 포트에서 본다.

```
curl -s http://localhost:8081/actuator/prometheus | grep coupon_issue_results_total
```

### 7. 되돌린다

```
docker exec -i freshmarket-mysql mysql --default-character-set=utf8mb4 \
  -ufreshmarket -pfreshmarket freshmarket < loadtest/reset.sql
```

그 다음 4번(이벤트 열기)을 다시 한다. `reset.sql` 이 `is_active` 를 내리는 것은 그래야 여는
API 가 Redis 를 다시 세우기 때문이다. 켜져 있으면 "이미 열렸다" 로 그냥 돌아가고 지난 회차의
카운터와 매핑이 그대로 남는다.

---

## 회차 기록 (로컬 리허설)

**아래 수치는 용량이 아니다.** k6, JVM, MySQL, Valkey 가 16GB 한 대에서 CPU 를 나눠 썼다.
`coupon.md` 3장의 "재서 정할 값" 은 이 환경에서 정하면 안 되고, 부하 발생기를 다른 호스트에
두고 운영과 비슷한 DB 에서 다시 재야 한다.

**기계와 무관하게 유효한 것은 정합성이다.** 아래 세 회차가 그것을 보여 준다.

### 회차 1. VU 20,000 / ramp 60s

```
k6      요청 20,000.  발급 208, 혼잡 11,279, 연결 실패 8,513
        p95 53초
앱      congested-seq-unavailable 14,896
DB      발급행 2,198, 구멍 19, pending 1,019
```

**기계가 먼저 넘어졌다.** Redis 호출이 300ms 타임아웃에 걸리기 시작하자 순번 확보 회로가
열렸고, 그때부터 요청 대부분이 번호를 못 받고 잘렸다. 부하 발생기가 같은 CPU 를 먹은 몫이 크다.

읽을 것이 있다면 **회로가 설계대로 열렸다는 것**이다. Redis 가 느려질 때 요청이 예산 2초를 다
태우고 실패하는 대신, 회로가 빨리 끊었다.

### 회차 2. VU 500 / ramp 30s

```
k6      발급 10,000, 소진 4,287, 혼잡 5,607
        p95 2.06초
앱      issued 10,000 / sold-out 4,287 / congested-budget 1,990
DB      발급행 10,000, 최대순번 10,000, 구멍 0, 회원수 10,000
```

**재고 1만에 2만 명이 몰려 정확히 1만 장이 나갔다.** 순번은 연속이고 1인 1매도 지켜졌다.

`congested-budget 1,990` 이 이 설계의 대가를 숫자로 보여 준다. **예산 2초를 넘겨 503 을 받았지만
그 티켓은 큐에 남아 결국 발급된 요청**이 1,990건이다. 사용자는 실패로 들었는데 쿠폰은 받았다.
반대쪽(예산을 넘기면 순번을 반납한다)을 고르면 도착 순서가 뒤집히므로 이쪽을 골랐다
([coupon.md](../docs/coupon/coupon.md) 9장).

### 회차 3. VU 500 / ramp 30s, 같은 조건 재실행

```
k6      발급 9,928, 소진 4,048, 혼잡 5,903, 연결 실패 121, 예상 밖 0
        p95 1.86초
앱      congested 전부 seq-unavailable
DB      발급행 9,928, 최대순번 10,000, 구멍 72
Redis   pending 73, 확정 표시 없는 매핑 72
```

같은 조건인데 회차 2 와 다르게 무너졌다. **로컬에서 회차마다 결과가 흔들린다는 증거**이고,
그래서 이 환경의 수치로 값을 정하면 안 된다.

대신 여기서 **회수가 도는 것을 확인했다.** 구멍 72개는 번호를 받고 행이 안 들어간 요청들이고,
그 번호가 `pending` 에 묶여 있었다. 매핑이 없는 회원 100명이 소진 상태에서 두드리자

```
pending 73 -> 0
발급행 9,928 -> 10,000
구멍 72 -> 0
```

**회수는 소진 상태에서 요청이 올 때만 돈다.** 시험이 끝나 트래픽이 끊기면 그 번호는 묶인 채로
남고, 요청이 다시 오면 그때 풀린다. 3장이 "주기 배치가 필요 없는 이유" 로 적은 것이 이것이다.

---

## 다음 회차에서 볼 것

이 환경에서 못 정한 것들이다. 부하 발생기를 분리한 뒤에 순서대로 옮겨 가며 잰다.

```
큐 상한         무한 -> 20000 -> 10000 -> 5000 -> 2500 -> 1000
플러시 스레드    1 부터 늘리며 큐 길이가 줄어드는지 본다.  풀이 5 라 그 위로는 커넥션을 못 얻는다
배치 윈도       1ms ~ 100ms
```

**`congested-budget` 과 `congested-seq-unavailable` 의 비율이 무엇을 조일지 알려 준다.**
앞이 크면 플러시가 못 따라가는 것이고, 뒤가 크면 Redis 쪽이다.
