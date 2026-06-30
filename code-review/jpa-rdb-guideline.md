# JPA 사용 리뷰 가이드 (RDB 관점 유지)

이 문서는 ORM(JPA)을 쓰되 관계형 DB의 관점을 잃지 않기 위한 점검 항목을 정리한 가이드다.
JPA 엔티티, 연관관계, 조회 코드가 포함되거나 변경되는 PR에 적용한다.

이 가이드의 점검 항목은 근거 문서 [jpa-rdb-rationale.md](./jpa-rdb-rationale.md)에서 코드 리뷰에 적용할 부분만 추출해 정리한 것이다.
각 항목의 배경과 상세한 설명은 근거 문서를 참고한다.

핵심 원칙은 하나다. DB는 관계형이지 객체지향이 아니므로, 객체지향적 환상을 DB에 강요하지 않는다.
JPA는 좋은 도구이지만 정해진 용도 안에서 쓸 때 그렇고, 그 선을 넘으면 DB 설계 감각을 잃게 만든다.

기준 스택은 Java, Spring Data JPA, MySQL 8.4이다.
이 가이드는 database.md(N+1, 인덱스, 트랜잭션)와 함께 본다. database.md가 쿼리와 트랜잭션의 정확성을 본다면, 이 문서는 엔티티 설계가 DB 구조를 투명하게 반영하는지를 본다.

## 1. 엔티티와 테이블의 대응

엔티티는 DB 구조를 그대로 드러내야 한다.

점검 항목
* 엔티티 하나가 테이블 하나에 대응하는 단순한 구조인가
  테이블을 보면 엔티티가 보이고, 엔티티를 보면 테이블이 보여야 한다.
* 외래 키를 연관 객체 매핑 대신 식별자 컬럼으로 그대로 반영하는 선택을 검토했는가
  연관 객체 매핑이 늘수록 실제로 어떤 쿼리가 나가는지 코드에서 가려진다.
* 비즈니스나 화면에 필요한 복합 객체를 엔티티에 욱여넣지 않고 별도 레이어(DTO, 서비스 조합)로 분리했는가

```java
// 엔티티는 DB 구조를 그대로 반영 (단순하게 유지)
@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;     // 외래 키를 식별자로 그대로 반영

    @Column(name = "total_price")
    private int totalPrice;
}
```

## 2. DB 구조를 가리는 자동화 기능

편의를 위한 자동 생성 기능은 DB 구조를 코드에서 보이지 않게 만든다.

점검 항목
* `@ManyToMany`로 조인 테이블을 자동 생성하지 않는가
  자동 생성된 조인 테이블의 컬럼명, 인덱스, 제약조건이 코드 어디에도 드러나지 않는다. 조인 엔티티를 명시적으로 두는 편이 낫다.
* 객체를 JSON 컬럼에 자동 매핑(`@JdbcTypeCode(SqlTypes.JSON)` 등)할 때, 그 컬럼 구조와 조회 방식을 이해하고 책임질 수 있는가
* 편해서 쓰는지, 만들어지는 DB 구조를 이해하고 쓰는지 구분했는가
  자동화 기능 자체가 금지는 아니다. 다만 생성되는 구조를 정확히 알고 쓰는 경우에만 허용한다.

```java
// 점검 대상: 조인 테이블이 자동 생성되어 구조가 코드에서 보이지 않음
@Entity
public class Student {
    @Id @GeneratedValue
    private Long id;

    @ManyToMany
    private List<Course> courses = new ArrayList<>();
}

// 개선: 조인 엔티티를 명시해 컬럼과 제약을 드러냄
@Entity
@Table(name = "student_course")
public class StudentCourse {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "course_id")
    private Long courseId;
}
```

## 3. cascade와 연관관계로 인한 숨은 쓰기

저장 한 번에 여러 테이블이 줄줄이 쓰이면, 실제로 나가는 쿼리가 코드에서 보이지 않는다.

점검 항목
* `cascade = CascadeType.ALL`과 깊은 연관관계로 save 한 번에 여러 테이블이 묶여 쓰이지 않는가
  무엇이 몇 건 INSERT되는지 호출부에서 드러나야 한다.
* 연관 객체의 저장을 의도적으로 분리해, 각 쓰기가 어떤 테이블에 일어나는지 추적 가능한가

```java
// 점검 대상: save 한 번에 order, order_item 여러 건, delivery까지 전부 INSERT
@Entity
public class Order {
    @Id @GeneratedValue
    private Long id;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToOne(cascade = CascadeType.ALL)
    private Delivery delivery;
}
```

## 4. 조회는 DB 관점으로

조회는 객체를 편하게 다루는 관점이 아니라, 어떤 쿼리가 어떻게 실행되는지의 관점으로 본다.

점검 항목
* 복합 조회를 엔티티 전체 로딩과 객체 그래프 탐색으로 풀지 않고, 필요한 컬럼만 DTO로 프로젝션하는가
* 화면용 복합 객체를 서비스나 DTO 조합에서 만들고, 엔티티는 데이터만 읽어 오는 역할에 두는가
* 성능 문제를 사후에 실행 계획으로 수습하기 전에, 설계 시점에 쿼리 형태를 고려했는가
  (N+1, 인덱스 활용 등 쿼리 차원의 점검은 database.md를 함께 본다.)

```java
// 필요한 컬럼만 DTO로 직접 조회 (엔티티 전체를 로딩하지 않음)
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("SELECT new com.example.dto.OrderItemDto(oi.name, oi.price, oi.count) " +
           "FROM OrderItem oi WHERE oi.orderId = :orderId")
    List<OrderItemDto> findItemDtosByOrderId(@Param("orderId") Long orderId);
}
```

```java
// 복합 객체는 서비스에서 데이터를 읽어 조합 (엔티티에 욱여넣지 않음)
@Transactional(readOnly = true)
public OrderDetailDto getOrderDetail(Long orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    Member member = memberRepository.findById(order.getMemberId()).orElseThrow();
    List<OrderItemDto> items = orderItemRepository.findItemDtosByOrderId(orderId);

    return new OrderDetailDto(order.getId(), member.getName(),
                              order.getTotalPrice(), items);
}
```

## 5. JPA 사용 범위

JPA를 쓰는 목적을 명확히 한정한다.

점검 항목
* JPA를 다음 세 가지 목적 안에서 쓰는가
  날 것의 SQL을 직접 쓰지 않기 위해, 파라미터 바인딩을 안전하게 처리하기 위해, 타입 안전하게 데이터를 읽기 위해.
* 메모리 객체 자동 분해, 깊은 cascade 저장, 조인 테이블 자동 생성, JSON 자동 매핑 같은 기능은, 만들어지는 DB 구조를 이해하고 책임질 수 있을 때만 쓰는가
* DTO가 늘고 코드가 길어지는 것을 감수하더라도, 자동 매핑에 맡겨 성능을 잃는 쪽을 택하지 않았는가
