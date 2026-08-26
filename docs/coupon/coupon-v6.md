# v6: 미리 넣은 행을 update 로 잡는다

`docs/coupon/coupon.md` 7장에서 뺀 절이다. 분량이 커져 본문의 버전 비교를 가리기에 따로 옮겼다.

용어와 제약은 [coupon.md](coupon.md) 를 전제한다. 특히 2장(DB 가 이미 지키는 것)과 3장(발급 한 건의 뼈대)을 먼저 읽어야 한다.

---

v1~v5 는 같은 스키마 위의 다섯 구현이다. **v6 은 스키마를 바꾼다.** 버전 표에는 함께 두되 사다리는 잇지 않으며, 비교할 때 이 전제를 함께 적는다.

`member_id` 를 NULL 허용으로 바꾸고, 이벤트 전에 `total_quantity` 만큼의 행을 `PENDING` 으로 미리 넣는다. 순번은 v2 처럼 Redis 가 주고, 발급은 insert 가 아니라 **그 순번의 행을 잡는 것**이다.

```sql
UPDATE member_coupon
   SET member_id = ?, status = 'ISSUED', issued_at = NOW(6), updated_at = NOW(6)
 WHERE coupon_id = ? AND issue_seq = ? AND status = 'PENDING';
```

`uk_mc_coupon_seq (coupon_id, issue_seq)` 의 정확 일치라 인덱스를 새로 만들 필요가 없다. `status = 'PENDING'` 조건 자체가 낙관적 락이므로 version 컬럼도 두지 않는다.

**묶는 방법이 INSERT 와 다르다.** INSERT 는 한 문장에 값을 나열하면 되는데 UPDATE 는 행마다 값이 달라 그렇게 안 된다.

| 방법 | 서버가 실행하는 문장 | 대가 |
|---|---|---|
| **JDBC batch** | N | 왕복 1회, 커밋 1회. 파싱과 실행이 N 번 |
| `CASE WHEN` 한 문장 | 1 | SQL 길이가 배치 크기에 비례한다 |
| `INSERT ... ON DUPLICATE KEY UPDATE` | 1 | PK 를 알아야 한다. 빗나가면 새 행이 생긴다 |

**JDBC batch 로 둔다.** 커밋 하나가 2~4ms 라 파싱 N 번은 그 뒤에 묻힌다. 다만 `rewriteBatchedStatements=true` 를 켜도 INSERT 처럼 한 문장으로 합쳐지지는 않으므로, **배칭이 줄여주는 것이 왕복과 커밋뿐이고 문장 비용은 남는다.** 세 번째 안은 미리 넣을 때 정해진 PK 를 앱이 알아야 성립하는데 순번만 들고 있으므로 조회가 하나 더 붙는다.

**Redis 가 죽으면 주소를 줄 곳이 없다.** 그때는 빈 행을 직접 찾는다.

```sql
SELECT issue_seq FROM member_coupon
 WHERE coupon_id = ? AND status = 'PENDING'
 ORDER BY issue_seq LIMIT N
 FOR UPDATE SKIP LOCKED;
```

`SKIP LOCKED` 는 남이 잡은 행을 건너뛰므로 대기가 없다. 대신 **번호를 정하는 것이 락을 먼저 잡은 순서**라 클라이언트 도착 순서와 어긋날 수 있다. 폴백에서는 어느 방식도 도착 순서를 보장하지 못하며, 그 판단은 [coupon.md](coupon.md) 7장에 있다. **`coupon` 한 행을 거치지 않으니 폴백에도 직렬화가 없다.** v1~v5 는 잡을 행이 없어 순번을 `coupon` 에서 받아야 하고, 그 락이 폴백의 천장이 된다.

전제가 둘이다.

```sql
KEY idx_pending (coupon_id, status, issue_seq)
```

**인덱스가 없으면 스캔하는 모든 행에 락을 걸려고 한다.** 300만 행에서 풀 스캔이 나면 개선이 아니라 장애다.

그리고 `SKIP LOCKED` 의 0행은 **"없음" 과 "지금 다 잠김" 을 구분하지 못한다.** 별도 조회로 확정한다.

```sql
SELECT 1 FROM member_coupon WHERE coupon_id = ? AND status = 'PENDING' LIMIT 1;
-- 0행이면 진짜 소진이다. 1행이면 잠겨 있을 뿐이니 다음 윈도우에 다시 본다
```

일반 조회는 락과 무관한 일관된 읽기라 **잠긴 행도 `PENDING` 으로 보인다.** 그래서 이 결과가 정확하다. 소진 근처에서만, 그것도 배치당 한 번 돈다.

폴백은 읽기가 하나 붙지만 **배칭하면 건당 비용이 사라진다.** `SELECT` 와 `UPDATE` 가 각각 한 번씩이라 배치 500건이면 건당 왕복이 0.004회다. 건별로 돌리면 10% 남짓 손해다.

지켜야 할 것이 셋이다. **스키마가 대신 지켜주지 않는 앱 코드 규율이다.**

```
1  SELECT 와 UPDATE 를 한 트랜잭션에 둔다
   나누면 락이 풀려 그 사이에 남이 같은 행을 잡는다. 이중 배정이 난다

2  배치 안에서 회원 중복을 제거한다
   한 사람이 연타하면 두 번째가 uk_mc_coupon_member 를 위반하고
   한 트랜잭션이라 배치 500건이 통째로 롤백된다

3  부분 실패 시 응답을 챙긴다
   롤백되면 잡았던 슬롯이 전부 PENDING 으로 되돌아가므로 데이터는 안전하다
   다만 그 요청들이 응답을 기다리므로 다음 윈도우로 넘기거나 실패로 끊는다
```

## 폴백 한 배치가 어떻게 흐르나

정상 모드의 순번 확보는 [coupon.md](coupon.md) 3장과 같다. 아래는 **Redis 가 죽어 폴백으로 넘어간 뒤**의 흐름이다.

**들어가고 나오는 것은 회로가 정한다.** Redis 호출이 타임아웃이나 연결 실패로 연속해서 깨지면 회로가 열리고, 그때부터 순번을 Redis 에 묻지 않는다. 닫히면 정상 모드로 돌아온다.

### 요청 스레드

```
1  폴백 소진 플래그를 본다.  서 있으면 큐에 넣지 않고 소진 응답
2  큐에 넣고 요청 예산만큼 잔다
```

**폴백 소진 플래그는 JVM 안의 값이다.** Redis 가 죽은 상황이라 거기 둘 수 없고, 두더라도 왕복을 없애려는 목적에 어긋난다. 인스턴스마다 따로 서지만, 늦게 아는 쪽은 확정 조회를 한 번 더 할 뿐이라 손해가 없다. 재시작이나 오토스케일로 비어 있어도 한 번 조회하고 다시 선다.

**정상 모드에서는 이 플래그를 쓰지 않는다.** `free` 에 번호가 반납되면 소진이 풀리기 때문이다. 폴백에는 반납 창구가 없고 커밋된 `ISSUED` 가 `PENDING` 으로 돌아가는 전이도 없어, **그때만 기억해도 된다.**

### 플러시 스레드 (윈도우마다 한 번)

```
1    트랜잭션을 열고 SKIP LOCKED 로 N 개를 시도한다
     N = 큐에 모인 수와 배치 크기 중 작은 쪽

2-A  k > 0    같은 트랜잭션에서 UPDATE 하고 커밋한다
              그 k 명의 응답을 완료시킨다
              나머지는 다음 윈도우로.  확정 조회는 돌리지 않는다

2-B  k = 0    잡은 락이 없으므로 트랜잭션을 끝내고 확정 조회를 한다
              1행 -> 잠겨 있을 뿐이다.  다음 윈도우
              0행 -> 폴백 소진 플래그를 세운다
                     큐에 대기 중인 요청 전부에 소진 응답을 내린다
```

**슬롯을 받았다는 것 자체가 재고가 있다는 증거다.** 그래서 확정 조회는 `k = 0` 일 때만 돈다. 0행으로 확정되면 큐에 남은 요청들이 더 기다릴 이유가 없으므로 한꺼번에 끊는다.

### 세 응답을 구분한다

| 응답 | 무엇이 정하나 | 재시도 |
|---|---|---|
| 발급 | 슬롯을 잡고 커밋까지 끝났다 | |
| **소진** | 확정 조회가 0행이었다 | 무의미하다 |
| **혼잡** | 요청 예산이 먼저 끝났다 | 가치 있다 |

**소진을 정하는 것은 확정 조회 하나뿐이고, 요청 예산은 언제 포기하는지만 정한다.** 둘을 섞으면 혼잡을 소진으로 답해 남은 재고를 못 팔거나, 소진을 혼잡으로 답해 끝난 이벤트에 재시도를 유도한다.

**혼잡으로 끊을 때 보정할 것이 없다.** 폴백이라 Redis 번호를 태우지 않았고 슬롯도 못 잡았다. 쓴 것이 없으므로 응답만 주고 끝낸다.

### 요청 예산

요청 스레드가 응답까지 기다리는 총 시간이다. 값은 부하 시험에서 정하되 **계층이 역전되면 안 된다.**

```
요청 예산  >  플러시 한 번 x 볼 윈도우 수
플러시 한 번 = 커넥션 획득(connection-timeout) + 조회와 UPDATE(socketTimeout) + 커밋
```

역전되면 요청 스레드가 먼저 포기해 혼잡으로 답한 뒤에 그 배치가 커밋된다. **실패했다고 답했는데 발급된 상태**가 되고, 사용자가 돌아오지 않으면 그 슬롯은 죽은 재고가 된다.

**재건 공식이 v1~v5 와 다르다.** 발급 수와 소비된 최대 순번을 구하는 식은 [coupon.md](coupon.md) 3장의 "발급 수를 어떻게 세나" 에 정리되어 있고, 아래는 그것을 이 버전에 적용한 것이다. 3장과 9장의 `MAX(issue_seq) + 1` 은 행이 발급될 때 생기는 구조를 전제한 값이다.

```
v6 은 1..10000 이 미리 들어 있다
-> MAX(issue_seq) 가 항상 10000 이다
-> 그대로 쓰면 카운터가 10001 이 되어 전부 소진으로 판정한다
```

```sql
-- v6 의 카운터 재건. ISSUED 만 본다
SELECT COALESCE(MAX(issue_seq), 0) + 1 FROM member_coupon
 WHERE coupon_id = ? AND status = 'ISSUED';
```

`free` 목록도 복구 시 다시 만든다. 페일오버로 승격된 노드에는 **폴백 중에 이미 채워진 번호가 남아 있어** 그대로 쓰면 0행만 나오고 헛돈다.

```sql
-- 진짜 구멍만 고른다. 이미 지나간 번호 중 아직 PENDING 인 것
SELECT issue_seq FROM member_coupon
 WHERE coupon_id = ? AND status = 'PENDING'
   AND issue_seq < (SELECT MAX(issue_seq) FROM member_coupon
                     WHERE coupon_id = ? AND status = 'ISSUED')
 ORDER BY issue_seq;
```

`idx_pending` 범위 조회라 싸다. v2~v5 는 구멍이 존재하지 않는 행이라 `1..MAX` 를 생성해 LEFT JOIN 해야 했다.

**얻는 것은 정확성이다.**

| | |
|---|---|
| 구멍 회수가 싸다 | 구멍이 `PENDING` 행으로 실재한다. 조회 하나로 목록이 나온다. v2~v5 는 `1..MAX` 를 생성해 LEFT JOIN 해야 한다 |
| 카운터 오차가 한 신호로 모인다 | 범위 밖 순번도, 되밀려 재발급된 순번도 예외가 아니라 0행이다 |
| 잔여가 조회 하나다 | `PENDING` 행 수가 곧 잔여다. `issued_quantity` 를 순번 발급기로 쓸 이유가 없어진다 |
| Redis 가 죽어도 락이 없다 | 빈 행을 `SKIP LOCKED` 로 잡는다. v1~v5 는 폴백에서 `coupon` 행 락에 묶인다 |

**내는 것**

| | |
|---|---|
| 스키마 | `member_id` NULL 허용 + 조건부 CHECK, `status` CHECK 3곳에 `PENDING` 추가 |
| 스냅샷 시점 | 발급 시점이 아니라 사전 삽입 시점의 쿠폰 조건이 박힌다 |
| 집계 | 회원을 안 끼는 집계에 `member_id IS NOT NULL` 이 붙는다 |

**`NOT NULL` 을 그냥 풀면 안 된다.** `member_coupon` 은 선착순만 담는 테이블이 아니다. 일반 쿠폰 발급 경로에서도 주인 없는 행이 들어갈 수 있게 된다.

```sql
CONSTRAINT chk_mc_pending_member CHECK (
    (status =  'PENDING' AND member_id IS NULL AND issued_at IS NULL)
 OR (status <> 'PENDING' AND member_id IS NOT NULL AND issued_at IS NOT NULL))
```

**`PENDING` 일 때만 NULL 을 허용하면 그 보호가 대부분 돌아온다.** `issued_at` 도 함께 묶어, 발급됐는데 시각이 없거나 그 반대인 행을 막는다.

**0행을 세 경우로 갈라야 한다.** insert 는 위반한 제약 이름이 원인을 알려주지만, update 는 서로 다른 상황을 0행 하나로 뭉갠다.

```
member_id 가 나    이미 내 것이다      -> 성공 응답. R4 가 여기서 지켜진다
member_id 가 남    카운터가 되밀렸다   -> 다음 순번을 받아 재시도
행이 없다          범위 밖 순번이다    -> 소진 거절
```

0행일 때만 그 행을 한 번 더 읽으면 갈린다. 정상 경로는 UPDATE 한 번 그대로다.

**성능은 v6 의 근거가 아니다.** 고정된 우측 말단 핫 페이지가 사라지는 것은 맞으나, 커밋 하나가 2~4ms(Multi-AZ 동기 복제)인데 행 하나의 쓰기 작업은 50~100us 다. 요청마다 커밋하면 그 차이가 묻히고, 배치로 커밋을 줄이면 이번에는 동시에 쓰는 주체가 줄어 다툴 상대가 없어진다. `undo` 는 오히려 v6 이 크다. **1만 장 규모에서는 차이의 부호도 확신할 수 없다.** 10만 이상에서 재볼 값이다.

**작업 셋이 확정되어 있어 미리 예열할 수 있다.** v1~v5 는 발급될 행이 아직 없어 올려둘 대상이 없다. v6 은 1만 행이 이미 있으므로 이벤트 전에 통째로 버퍼 풀에 넣어둘 수 있다.

`LOAD INDEX INTO CACHE` 는 MyISAM 전용이라 InnoDB 에는 안 먹는다. 읽어서 올린다.

```sql
-- 순번으로 행을 찾는 경로다
SELECT COUNT(*) FROM member_coupon FORCE INDEX (uk_mc_coupon_seq)
 WHERE coupon_id = ?;

-- status 는 uk_mc_coupon_seq 에 없어 행까지 읽으므로 클러스터드 인덱스가 올라온다
SELECT COUNT(status) FROM member_coupon FORCE INDEX (uk_mc_coupon_seq)
 WHERE coupon_id = ?;

-- 폴백 경로다
SELECT COUNT(*) FROM member_coupon FORCE INDEX (idx_pending)
 WHERE coupon_id = ? AND status = 'PENDING';

-- UPDATE 가 member_id 를 바꾸므로 이 인덱스도 갱신 대상이다
SELECT COUNT(*) FROM member_coupon FORCE INDEX (uk_mc_coupon_member)
 WHERE coupon_id = ?;
```

**마지막 것이 빠지기 쉽다.** `member_id` 를 NULL 에서 값으로 바꾸는 것은 `uk_mc_coupon_member` 에서 `(coupon_id, NULL)` 을 지우고 `(coupon_id, 회원)` 을 넣는 일이라, 그 인덱스도 1만 번 갱신된다.

**1초 이상 띄워 두 번 돌린다.** InnoDB 는 새로 읽은 페이지를 old 서브리스트에 넣어 풀 스캔이 캐시를 쓸어가는 것을 막는데, 예열도 같은 취급을 받는다. 한 번만 돌리면 올려둔 페이지가 old 에 남아 제일 먼저 쫓겨난다. `innodb_old_blocks_time` 기본값이 1000ms 라 그만큼 지난 뒤 다시 읽어야 young 으로 승격된다.

용량은 부담이 아니다. 1만 행에 인덱스까지 10MB 아래이고 버퍼 풀은 약 700MB 다. 한 쿠폰의 1만 행이 `uk_mc_coupon_seq` 상 연속이라 페이지 수도 적다.

**페일오버 뒤에는 다시 돌린다.** 9장의 RDS 페일오버는 버퍼 풀이 빈 인스턴스로 넘어가는 것이라 예열이 통째로 날아간다. 재개 직후 첫 배치들이 디스크를 읽게 되므로, 승격을 감지하면 예열을 다시 돌리는 절차가 필요하다.

**측정에서는 예열 있음과 없음을 나눠 잰다.** 안 그러면 v6 이 빨라 보인 것이 예열 덕인지 update 덕인지 가릴 수 없다.
