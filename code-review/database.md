# 데이터베이스 리뷰 가이드

Repository, Entity, SQL, 마이그레이션 변경에 적용한다.
쿼리 효율, 인덱스, 트랜잭션, JPA N+1을 다룬다.
기준 RDB는 MySQL 8.4이다.

## 1. JPA N+1

연관 엔티티를 루프마다 조회하여 쿼리가 폭증하는지 본다.

점검 항목
* findAll 등 컬렉션 조회 후 루프에서 연관 엔티티에 접근하는가
* 지연 로딩 연관을 화면 단에서 반복 접근하는가
* 적절히 fetch join 또는 @EntityGraph를 적용했는가

```java
// 점검 대상: 지연 로딩으로 N+1 발생
List<Order> orders = orderRepository.findAll();
for (Order o : orders) {
    o.getMember().getName();  // 루프마다 추가 쿼리
}

// 개선: fetch join으로 한 번에 조회
@Query("SELECT o FROM Order o JOIN FETCH o.member")
List<Order> findAllWithMember();
```

## 2. 인덱스 활용

쿼리가 인덱스를 탈 수 있는 형태인지 본다.

점검 항목
* WHERE 절 컬럼에 함수를 씌워 인덱스를 무력화하지 않는가
* 조회 조건에 맞는 인덱스가 존재하는가
* LIKE 검색에서 앞부분 와일드카드로 인덱스를 무력화하지 않는가

```sql
-- 점검 대상: created_at에 함수를 씌워 인덱스 활용 불가
SELECT * FROM users WHERE DATE(created_at) = '2026-01-01';

-- 개선: 범위 조건으로 변경하여 인덱스 활용
SELECT * FROM users
WHERE created_at >= '2026-01-01 00:00:00'
  AND created_at <  '2026-01-02 00:00:00';
```

```sql
-- 점검 대상: 앞부분 와일드카드로 인덱스 사용 불가
SELECT * FROM users WHERE name LIKE '%kim';

-- 개선: 접두 검색으로 변경하거나 전문 검색 인덱스 검토
SELECT * FROM users WHERE name LIKE 'kim%';
```

## 3. 트랜잭션

트랜잭션 경계와 롤백 동작이 의도대로인지 본다.

점검 항목
* `@Transactional` 범위가 비즈니스 단위와 일치하는가
* checked 예외 발생 시 롤백 동작이 의도대로인가(Spring 기본은 unchecked만 롤백)
* 조회 전용 메서드에 readOnly 속성을 적용했는가
* 트랜잭션 안에서 외부 API 호출 등 긴 작업으로 커넥션을 오래 점유하지 않는가

```java
// 점검 대상: checked 예외는 기본적으로 롤백되지 않음
@Transactional
public void process(Order order) throws IOException {
    orderRepository.save(order);
    fileExporter.export(order);  // IOException 발생 시 save가 커밋됨
}

// 개선: rollbackFor 지정
@Transactional(rollbackFor = IOException.class)
public void process(Order order) throws IOException {
    orderRepository.save(order);
    fileExporter.export(order);
}
```

## 4. 스키마와 마이그레이션

DDL 변경이 운영 환경에서 안전한지 본다.

점검 항목
* 대용량 테이블에 락을 길게 유발하는 DDL을 주의했는가
* 컬럼 추가 시 기본값과 NULL 허용 여부가 적절한가
* 마이그레이션이 롤백 가능하거나 되돌릴 절차가 마련되어 있는가
* 인덱스 추가가 기존 쿼리 성능에 미치는 영향을 검토했는가
