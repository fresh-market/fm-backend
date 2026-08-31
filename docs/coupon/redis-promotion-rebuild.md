# 선착순 쿠폰: Redis 승격 시 재건 절차

**운영 중에 여는 문서다.** 왜 이렇게 하는지는 [coupon.md](coupon.md) 10장에 있고, 여기에는
무엇을 보고 무엇을 하는지만 적는다.

```
freshmarket-cache   Valkey 9.0.0, 노드 둘 (2a / 2c), cache.t4g.micro
Multi-AZ            enabled.  자동 페일오버가 있다
appendonly          no.  지속성이 없어 동시 재시작이면 전부 사라진다
```

## 1. 증상

**발급이 전부 503 이 된다.** 응답 코드가 `COUPON-006` 이고 소진(`COUPON-005`, 409)이 아니다.

지표에서 한 갈래만 치솟는다.

```
coupon_issue_results_total{result="congested-not-prepared"}
```

**평상시 이 값은 0 에 가깝다.** 관리자가 아직 안 연 이벤트를 찌를 때만 나오기 때문이다.
이벤트가 도는 중에 이 값이 오르면 **Redis 가 카운터를 잃은 것**이다.

`sold-out` 이 오르는 것은 다른 이야기다. 그쪽은 정상적인 소진이다.

## 2. 먼저 확인한다

### 페일오버가 있었나

```bash
aws elasticache describe-events --source-identifier freshmarket-cache \
  --source-type replication-group --duration 60
```

### 키가 있나

```bash
valkey-cli -h "$VALKEY_HOST" EXISTS coupon:{쿠폰ID}:counter
```

```
0   카운터가 없다.  재건 대상이다
1   카운터는 있다.  3절로 간다
```

### 카운터가 DB 보다 뒤처졌나

**카운터가 있어도 안심할 수 없다.** 아래 부등식은 깨질 수 없는 것이다.

```
counter >= MAX(issue_seq)
```

번호는 Redis 가 내준 뒤에야 DB 행이 되기 때문이다. 두 값을 직접 견준다.

```bash
valkey-cli -h "$VALKEY_HOST" GET coupon:{쿠폰ID}:counter
```

```sql
SELECT MAX(issue_seq) FROM member_coupon WHERE coupon_id = {쿠폰ID};
```

**카운터가 더 작으면 Redis 가 뒤처진 것이고, 차이가 곧 뒤처진 양이다.** 이 경우는 자동 복구가
안 걸린다(6절).

## 3. 자동 복구를 관찰한다

**요청이 하나라도 들어오면 앱이 스스로 시작한다.** 사람이 누를 것이 없다.

로그를 이 순서로 본다.

| 이벤트 | 뜻 |
|---|---|
| `COUPON_SEQ_REBUILD_STARTED` | 락을 잡았고 조용해지기를 기다리는 중이다 |
| `COUPON_SEQ_REBUILT` | 끝났다. `issued`, `maxSeq`, `freed` 가 함께 찍힌다 |
| `COUPON_SEQ_REBUILD_SKIPPED` | 기다리는 동안 카운터가 생겼다. 남이 먼저 했다 |
| `COUPON_SEQ_REBUILD_FAILED` | 실패했다. 다음 요청이 다시 띄운다 |

**`STARTED` 와 `REBUILT` 사이가 대기 시간이다.** 진행 중인 발급이 결판나기를 기다리는 구간이고,
`coupon.issue.reclaim-after` 가 그 길이를 정한다(운영값 60초).

**요청이 아예 안 들어오면 안 돈다.** 그때는 발급 엔드포인트를 한 번 찌르면 시작된다.

## 4. 자동 복구가 안 될 때

### `STARTED` 가 안 찍힌다

**요청이 그 인스턴스에 안 닿았을 수 있다.** 발급 엔드포인트를 몇 번 찌른다.

**락이 남아 있을 수도 있다.** 앞선 시도가 비정상 종료하면 TTL 이 풀릴 때까지 남는다.

```bash
valkey-cli -h "$VALKEY_HOST" TTL coupon:{쿠폰ID}:rebuild
valkey-cli -h "$VALKEY_HOST" DEL coupon:{쿠폰ID}:rebuild
```

### `FAILED` 가 반복된다

**DB 를 못 읽는 경우가 대부분이다.** 재건이 `member_coupon` 을 통째로 읽으므로 RDS 쪽을 먼저 본다.
DB 가 돌아오면 다음 요청이 알아서 다시 띄운다.

### 쿠폰이 조건에 안 맞는다

재건은 **켜져 있고 선착순인 쿠폰만** 다시 세운다. 꺼진 이벤트에 카운터를 세우면 열지도 않은
이벤트가 발급을 시작하기 때문이다.

```sql
SELECT is_active, total_quantity, issue_end_at FROM coupon WHERE coupon_id = {쿠폰ID};
```

## 5. 절대 하면 안 되는 것

**관리자 API 로 이벤트를 껐다 켜지 않는다.**

이벤트를 여는 절차는 `prepare` 를 부르고, `prepare` 는 **`clear` 로 네 키를 먼저 지운 뒤 카운터를
0 으로 세운다.** 그러면 살아남은 매핑까지 사라지고 **1번부터 다시 나가서**, 이미 발급된 번호와
정면으로 부딪힌다. `uk_mc_coupon_seq` 가 막아 주지만 그 뒤로 들어오는 요청이 줄줄이 실패한다.

**카운터를 손으로 세우지 않는다.** `seq` 와 `free` 를 함께 세우지 않으면 이미 받은 사람이 다시
번호를 받고, 구멍이 영영 안 메워진다.

## 6. 카운터가 뒤처졌을 때 (자동 복구가 안 걸리는 경우)

**카운터가 있어서 `-2` 가 안 나므로 아무도 감지하지 못한다.** LRU 축출이나 복제 지연 잔여가
원인이다.

증상은 **이미 행이 있는 번호가 다시 나가는 것**이다. 발급이 되다 말다 하고, 로그에 순번 충돌이
반복된다. 카운터가 실제 최댓값을 넘길 때까지 이어진다.

**지금은 자동 대응이 없다.** 당장의 조치는 카운터를 실제 최댓값까지 밀어 올리는 것이다.

```sql
SELECT MAX(issue_seq) FROM member_coupon WHERE coupon_id = {쿠폰ID};
```

```bash
valkey-cli -h "$VALKEY_HOST" SET coupon:{쿠폰ID}:counter {그 값} KEEPTTL
```

**`KEEPTTL` 을 빼면 수명이 사라진다.** 네 키는 마감에서 온 만료를 함께 들고 있어야 한다.

**이것은 응급 조치다.** `seq` 해시도 함께 뒤처져 있으면 이미 받은 사람이 다시 받으려 하고,
`uk_mc_coupon_member` 에 걸려 번호를 태운다. 이벤트가 끝난 뒤 7절로 실제 피해를 확인한다.

## 7. 복구 뒤 검증

```sql
SELECT COUNT(*)                                AS 발급행,
       COALESCE(MAX(issue_seq), 0)             AS 최대순번,
       COALESCE(MAX(issue_seq), 0) - COUNT(*)  AS 구멍,
       COUNT(DISTINCT member_id)               AS 회원수
  FROM member_coupon WHERE coupon_id = {쿠폰ID};
```

| 값 | 정상 |
|---|---|
| 발급행 | `total_quantity` 이하 |
| 구멍 | 0 이 목표. 이벤트가 도는 중이면 0 이 아닐 수 있다 |
| 회원수 | 발급행과 같다. 다르면 1인 2매다 |

**구멍은 이벤트가 도는 동안에는 정상이다.** 번호를 받고 아직 커밋 안 된 사람들이 그만큼 있다.
**소진 시점에 회수가 되살리므로** 마감 뒤에도 남아 있으면 그때 실제 손실이다.

부등식도 다시 본다.

```
counter >= MAX(issue_seq)
```

## 8. 이벤트가 이미 마감됐다면

**아무것도 안 해도 된다.** 종료 배치가 마감에서 60초 뒤에 이벤트를 끄고 발급 수를 실제 행 수로
맞춘 뒤 네 키를 지운다. 배치가 안 돌아도 네 키에 걸린 만료(`issue_end_at` + 1분)가 그물로 받는다.

**다만 그때의 구멍은 회수되지 않는다.** 회수는 소진 상태에서 요청이 올 때만 돌기 때문이다.
그만큼 덜 팔린 것으로 남는다.

## 9. 사후에 남길 것

```
페일오버 시각과 감지까지 걸린 시간
congested-not-prepared 가 오른 구간의 길이
재건 로그의 issued / maxSeq / freed
마감 뒤의 구멍 수
```

**감지까지 걸린 시간이 가장 중요하다.** 요청이 들어와야 감지되는 구조라, 트래픽이 뜸한 구간에
페일오버가 나면 그만큼 늦어진다.
