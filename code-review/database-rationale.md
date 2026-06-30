# 데이터베이스 점검 항목의 근거

이 문서는 [database.md](./database.md)의 각 점검 항목이 왜 필요한지를 구체적인 예시와 함께 설명한다.
DB 관련 문제는 코드 리뷰에서 놓치면 운영 환경의 부하나 데이터 정합성 사고로 직결되므로, 근거를 이해하고 보는 것이 중요하다.
기준 RDB는 MySQL 8.4이다.

## 1. JPA N+1

### 왜 N+1을 막아야 하는가
N+1은 목록 하나를 조회한 뒤 각 항목의 연관 데이터를 개별 쿼리로 가져오는 문제다.

```java
List<Order> orders = orderRepository.findAll();   // (1) 주문 목록 조회: 쿼리 1번
for (Order o : orders) {
    o.getMember().getName();   // (N) 주문마다 회원 조회: 주문이 100개면 쿼리 100번
}
// 총 1 + 100 = 101번의 쿼리가 나간다.
```

실제로 나가는 쿼리는 이런 모양이 된다.

```sql
SELECT * FROM orders;                       -- 1번
SELECT * FROM member WHERE id = 1;          -- 주문 1의 회원
SELECT * FROM member WHERE id = 2;          -- 주문 2의 회원
-- ... 주문 수만큼 반복
```

개발 환경에서는 데이터가 적어 멀쩡해 보이다가, 운영에서 주문이 쌓이면 같은 화면이 수백 번의 쿼리를 유발해 응답이 급격히 느려진다.
드러나는 시점이 늦고 코드만 봐서는 알아채기 어려워서, 리뷰 단계에서 잡는 것이 가장 싸다.

### 왜 fetch join이나 EntityGraph로 푸는가
지연 로딩을 그대로 두고 반복 접근하면 N+1이 되고, 즉시 로딩으로 일괄 전환하면 필요 없을 때도 항상 조인이 걸린다.

```java
// fetch join: 필요한 조회에서만 명시적으로 한 번에 가져옴 (쿼리 1번)
@Query("SELECT o FROM Order o JOIN FETCH o.member")
List<Order> findAllWithMember();
```

fetch join은 필요한 쿼리에서만 함께 가져오므로 N+1과 무조건 즉시 로딩 사이의 절충이 된다.

## 2. 인덱스 활용

### 왜 컬럼에 함수를 씌우면 안 되는가
인덱스는 컬럼의 원래 값을 정렬해 둔 자료구조다. WHERE 절에서 컬럼을 함수로 가공하면, DB는 가공된 결과로 비교해야 해서 정렬된 원본 인덱스를 쓸 수 없다.

```sql
-- 점검 대상: created_at 에 DATE() 를 씌워 인덱스를 못 탐 → 전체 행 스캔
SELECT * FROM users WHERE DATE(created_at) = '2026-01-01';

-- 개선: 컬럼 원본을 그대로 비교하는 범위 조건 → 인덱스 사용
SELECT * FROM users
WHERE created_at >= '2026-01-01 00:00:00'
  AND created_at <  '2026-01-02 00:00:00';
```

EXPLAIN으로 확인하면 전자는 `type: ALL`(풀 스캔), 후자는 `type: range`(인덱스 범위 스캔)로 나뉜다.

### 왜 적절한 인덱스가 있어야 하는가
인덱스가 없으면 조회마다 테이블 전체를 읽는다. 데이터가 적을 때는 차이가 없지만, 행이 많아지면 같은 쿼리의 비용이 선형으로 커진다.

```sql
-- member_id 에 인덱스가 없으면, 주문 1000만 건을 모두 훑어 42번 회원의 주문을 찾는다.
SELECT * FROM orders WHERE member_id = 42;

-- 인덱스를 만들면 해당 행만 바로 찾아간다.
CREATE INDEX idx_orders_member_id ON orders (member_id);
```

자주 조회되는 조건에 인덱스가 있는지 보는 것은 미래의 성능 절벽을 미리 막는 일이다.

### 왜 앞부분 와일드카드를 피하는가
`LIKE 'kim%'`는 인덱스의 정렬 순서를 따라 시작 지점을 찾을 수 있다.

```sql
-- 인덱스 활용 가능: 'kim' 으로 시작하는 지점부터 읽음
SELECT * FROM users WHERE name LIKE 'kim%';

-- 점검 대상: 어디서 시작할지 알 수 없어 전부 훑음 (인덱스 무력화)
SELECT * FROM users WHERE name LIKE '%kim';
```

부분 일치 검색이 꼭 필요하면 전문 검색 인덱스 같은 다른 수단을 검토해야 한다.

## 3. 트랜잭션

### 왜 트랜잭션 범위가 비즈니스 단위와 맞아야 하는가
하나의 비즈니스 작업이 여러 트랜잭션으로 쪼개지면, 중간에 실패했을 때 일부만 반영되어 데이터가 어긋난다.

```java
// 점검 대상: 출금과 입금이 별개 트랜잭션이면, 출금 후 입금 실패 시 돈이 사라진다
@Transactional public void withdraw(Long from, int amount) { ... }
@Transactional public void deposit(Long to, int amount) { ... }
public void transfer(...) { withdraw(...); deposit(...); }  // 두 트랜잭션으로 쪼개짐

// 개선: 함께 성공하거나 함께 실패해야 하는 범위를 하나의 트랜잭션으로
@Transactional
public void transfer(Long from, Long to, int amount) { ...; ...; }
```

반대로 범위가 너무 넓으면 커넥션을 오래 잡아 동시 처리량이 떨어진다.

### 왜 checked 예외 롤백을 확인해야 하는가
Spring의 `@Transactional`은 기본적으로 unchecked 예외에서만 롤백한다.

```java
// 점검 대상: IOException(checked) 발생 시 save 가 롤백되지 않고 커밋됨
@Transactional
public void process(Order order) throws IOException {
    orderRepository.save(order);
    fileExporter.export(order);   // 여기서 IOException → save 는 이미 커밋되어 데이터가 남음
}

// 개선: rollbackFor 로 명시
@Transactional(rollbackFor = IOException.class)
public void process(Order order) throws IOException { ... }
```

이 차이는 코드만 봐서는 드러나지 않아 사고로 이어지기 쉬우므로 명시적으로 확인해야 한다.

### 왜 조회 전용에 readOnly를 다는가
`@Transactional(readOnly = true)`는 변경 감지(dirty checking)용 스냅샷을 만들지 않아 메모리와 CPU를 아낀다.
또한 "이 메서드는 데이터를 바꾸지 않는다"는 의도를 코드로 드러내어 읽는 사람에게 알린다.

### 왜 트랜잭션 안에서 긴 작업을 피하는가
트랜잭션이 열려 있는 동안 DB 커넥션이 점유된다.

```java
// 점검 대상: 트랜잭션 안에서 외부 API 호출(수 초 소요) → 그동안 커넥션을 붙잡음
@Transactional
public void pay(Order order) {
    order.markPaid();
    externalPgClient.request(order);   // 느린 외부 호출. 동시 요청이 몰리면 커넥션 풀 고갈
}
```

커넥션 풀이 고갈되면 DB를 쓰려는 다른 요청까지 대기하게 되므로, 긴 작업은 트랜잭션 밖으로 빼는 것이 안전하다.

## 4. 스키마와 마이그레이션

### 왜 대용량 테이블 DDL을 주의하는가
일부 DDL은 실행 중 테이블에 락을 걸어 읽기나 쓰기를 막는다.

```sql
-- 수천만 행 테이블에서 이 작업이 수십 초 걸리면, 그동안 해당 테이블 쓰기가 멈출 수 있다
ALTER TABLE orders ADD COLUMN memo VARCHAR(255);
```

작은 테이블이면 순식간이지만 큰 테이블에서는 그 시간이 길어져 서비스가 멈춘 것처럼 보일 수 있으므로, 대상 테이블의 규모와 락 영향을 미리 따져야 무중단 배포를 지킬 수 있다.

### 왜 컬럼 추가 시 기본값과 NULL 여부를 보는가
기존 행이 있는 테이블에 NOT NULL 컬럼을 기본값 없이 추가하면 기존 데이터가 제약을 위반한다.

```sql
-- 점검 대상: 기존 행의 status 를 채울 값이 없어 적용 실패하거나 빈 값이 들어감
ALTER TABLE orders ADD COLUMN status VARCHAR(20) NOT NULL;

-- 개선: 기본값을 함께 지정
ALTER TABLE orders ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CREATED';
```

### 왜 롤백 가능성을 따지는가
배포 후 문제가 생기면 빠르게 되돌릴 수 있어야 피해를 줄인다.
컬럼 삭제처럼 데이터가 사라지는 마이그레이션은 되돌릴 수 없으므로, 사전 백업이나 단계적 적용(먼저 사용 중단 후 일정 기간 뒤 삭제) 같은 대비책이 필요하다.

### 왜 인덱스 추가의 영향을 검토하는가
인덱스는 조회를 빠르게 하지만 INSERT, UPDATE, DELETE 때마다 인덱스도 갱신해야 해서 쓰기를 느리게 하고 저장 공간을 더 쓴다.
쓰기가 잦은 테이블에 인덱스를 무분별하게 추가하면 쓰기 성능이 떨어지므로, 얻는 조회 이득과 치르는 쓰기 비용을 함께 봐야 한다.
