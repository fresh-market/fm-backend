# JPA 사용 리뷰 가이드 (RDB 관점 유지)

이 문서는 ORM(JPA)을 쓰되 관계형 DB의 관점을 잃지 않기 위한 점검 항목을 정리한 가이드다.
JPA 엔티티, 연관관계, 조회 코드가 포함되거나 변경되는 PR에 적용한다.

이 가이드의 점검 항목은 근거 문서 [jpa-rdb-rationale.md](./jpa-rdb-rationale.md)에서 코드 리뷰에 적용할 부분만 추출해 정리한 것이다.
각 항목의 배경과 상세한 설명은 근거 문서를 참고한다.

핵심 원칙은 하나다. DB는 관계형이지 객체지향이 아니므로, 객체지향적 환상을 DB에 강요하지 않는다.
JPA는 좋은 도구이지만 정해진 용도 안에서 쓸 때 그렇고, 그 선을 넘으면 DB 설계 감각을 잃게 만든다.

기준 스택은 Java, Spring Data JPA, MySQL 8.4이다.
이 가이드는 database.md(N+1, 인덱스, 트랜잭션)와 함께 본다. database.md가 쿼리와 트랜잭션의 정확성을 본다면, 이 문서는 엔티티 설계가 DB 구조를 투명하게 반영하는지를 본다.

## 1. JPA를 적극 활용할 영역

JPA의 장점이 분명한 영역에서는 오히려 적극적으로 활용한다. 아래는 JPA가 실수를 줄여 주는 지점이다.

점검 항목
* 파라미터 바인딩을 JPA에 맡겨 SQL 인젝션 위험을 줄였는가
  날 것의 SQL을 문자열로 조합하지 않고 JPA의 바인딩을 활용한다.
* 컬럼 타입과 필드 타입의 매핑으로 타입 안전하게 데이터를 다루는가
  컴파일 시점에 많은 실수가 걸러진다.
* 반복적인 기본 CRUD(INSERT, UPDATE, SELECT)를 직접 작성하는 대신 JPA에 맡겼는가
  기본 CRUD 자동화는 JPA의 분명한 장점이므로 이 영역에서는 적극 활용한다.
* 스키마 변경을 도구로 추적하고 관리하는가(마이그레이션 관리)

```java
// JPA가 잘하는 영역: 단순 매핑과 기본 CRUD, 안전한 파라미터 바인딩
@Entity
@Table(name = "member")
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;
}
```

```java
// SQL을 직접 쓰지 않고 객체로 저장/조회 (바인딩과 타입 안전성은 JPA가 처리)
memberRepository.save(member);
Member found = memberRepository.findById(1L).orElseThrow();
```

## 2. 엔티티와 테이블의 1대1 대응

ORM이 가장 빛나는 순간은 테이블 구조를 그대로 매핑할 때다. 엔티티는 DB 구조를 투명하게 드러내야 한다.

점검 항목
* 엔티티 하나가 테이블 하나에 단순하게 대응하는가
  테이블을 보면 엔티티가 보이고, 엔티티를 보면 테이블이 보여야 한다.
* 외래 키를 연관 객체 매핑 대신 식별자 컬럼으로 그대로 반영하는 선택을 검토했는가
  DB 구조가 코드에 그대로 드러나도록 유지한다.

```java
// member 테이블 <-> Member 엔티티 (1대1 대응)
@Entity
@Table(name = "member")
public class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
}

// orders 테이블 <-> Order 엔티티 (1대1 대응)
@Entity
@Table(name = "orders")
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;   // 외래 키를 식별자로 그대로 반영
}
```

## 3. DB 구조를 가리는 부가 기능

매핑, 변경 추적, 지연 로딩, 마이그레이션 같은 핵심 기술 위에 얹힌 부가 기능은 DB 구조를 코드에서 보이지 않게 만든다.
"이게 정말 필요한가"를 먼저 묻고, 무비판적으로 "어, 되네" 하고 넘어가지 않는다.

점검 항목
* `@ManyToMany`로 조인 테이블을 자동 생성하지 않는가
  자동 생성된 조인 테이블의 컬럼명, 인덱스, 제약조건이 코드 어디에도 드러나지 않는다. DB를 직접 열어 보기 전까지 구조를 알 수 없다.
* 객체를 JSON 컬럼에 자동 매핑(`@JdbcTypeCode(SqlTypes.JSON)` 등)할 때, 그 컬럼 구조와 조회 방식을 이해하고 책임질 수 있는가
* "편해서" 쓰는지, 만들어지는 DB 구조를 "이해하고" 쓰는지 구분했는가
  기능 자체가 금지는 아니다. 생성되는 구조를 정확히 알고 책임질 수 있을 때만 쓴다.

```java
// 점검 대상: 조인 테이블(student_courses)이 자동 생성되어 구조가 코드에서 보이지 않음
@Entity
public class Student {
    @Id @GeneratedValue
    private Long id;

    @ManyToMany
    private List<Course> courses = new ArrayList<>();
}
```

```java
// 점검 대상: 객체를 JSON 컬럼에 자동 직렬화/역직렬화. 컬럼 구조와 조회 방식을 이해하고 있는가
@Entity
public class Product {
    @Id @GeneratedValue
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    private ProductOptions options;
}
```

```java
// 개선: 조인 엔티티를 명시해 컬럼과 제약을 코드에 드러냄
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

## 4. cascade와 연관관계로 인한 숨은 쓰기

cascade와 연관관계를 깊게 걸어 두면 저장 한 번에 여러 테이블이 줄줄이 쓰인다.
편해 보이지만 그 순간 무엇이 몇 건 쓰이는지가 코드에서 사라지고, DB 설계 감각을 잃게 된다.

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

```java
// 저장 한 번에 여러 테이블이 쓰이지만, 무엇이 몇 건 쓰이는지 이 호출만 봐서는 알 수 없다
orderRepository.save(order);
```

두 패러다임은 사고의 중심이 다르다. 관계형 DB는 정규화, 인덱스, 조인 전략, 쿼리 실행 계획을 중심에 두고 성능의 관건이 "어떤 쿼리가 어떻게 실행되는가"인 반면, 객체 중심 사고는 객체 그래프와 참조를 중심에 두고 "객체를 얼마나 편하게 다루는가"를 본다.
객체 중심으로 DB를 다루면 나중에 느려진 뒤에야 실행 계획을 들여다보게 된다.

```sql
-- MySQL 8.4에서 느려진 쿼리의 실행 계획을 뒤늦게 확인하는 상황
EXPLAIN ANALYZE
SELECT * FROM orders o
JOIN order_item oi ON oi.order_id = o.id
WHERE o.member_id = 42;
```

이 확인 자체가 나쁜 것은 아니다. 문제는 객체 추상화에 취해 있다가 사고가 난 뒤에야 하게 된다는 점이다.

## 5. 조회는 DB 관점으로, 복합 객체는 별도 레이어

엔티티는 DB 구조를 그대로 반영하는 단순한 형태로 두고, 화면이나 비즈니스 로직에 필요한 복합 객체는 엔티티가 아니라 별도 레이어에서 만든다.
DB에서는 데이터만 읽어 오고, 그 데이터를 조합해 최종 객체로 반환한다.

점검 항목
* 복합 객체를 엔티티에 욱여넣지 않고 별도 DTO로 정의했는가
* 화면용 복합 객체를 서비스나 DTO 조합에서 만들고, 엔티티는 데이터만 읽어 오는 역할에 두는가
* 복잡한 조회를 엔티티 전체 로딩과 객체 그래프 탐색으로 풀지 않고, 필요한 컬럼만 DTO로 프로젝션하는가
* 성능 문제를 사후에 실행 계획으로 수습하기 전에, 설계 시점에 쿼리 형태를 고려했는가

```java
// 엔티티는 DB 구조를 그대로 반영 (단순하게 유지)
@Entity
@Table(name = "orders")
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "total_price")
    private int totalPrice;
}
```

```java
// 비즈니스 로직/화면에 필요한 복합 객체는 별도 DTO로 정의
public class OrderDetailDto {
    private Long orderId;
    private String memberName;
    private int totalPrice;
    private List<OrderItemDto> items;

    public OrderDetailDto(Long orderId, String memberName,
                          int totalPrice, List<OrderItemDto> items) {
        this.orderId = orderId;
        this.memberName = memberName;
        this.totalPrice = totalPrice;
        this.items = items;
    }
    // getter 생략
}
```

```java
// 서비스에서 필요한 데이터만 읽어 조합 (엔티티에 욱여넣지 않음)
@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderQueryService(OrderRepository orderRepository,
                             MemberRepository memberRepository,
                             OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.memberRepository = memberRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Transactional(readOnly = true)
    public OrderDetailDto getOrderDetail(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        Member member = memberRepository.findById(order.getMemberId()).orElseThrow();
        List<OrderItemDto> items = orderItemRepository.findItemDtosByOrderId(orderId);

        return new OrderDetailDto(order.getId(), member.getName(),
                                  order.getTotalPrice(), items);
    }
}
```

```java
// 복잡한 조회는 필요한 컬럼만 DTO로 직접 프로젝션 (엔티티 전체를 로딩하지 않음)
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("SELECT new com.example.dto.OrderItemDto(oi.name, oi.price, oi.count) " +
           "FROM OrderItem oi WHERE oi.orderId = :orderId")
    List<OrderItemDto> findItemDtosByOrderId(@Param("orderId") Long orderId);
}
```

이 방식은 DTO가 늘고 코드가 길어지는 대가가 있다.
점검 항목
* DTO가 늘고 손이 더 가는 것을 감수하더라도, 자동 매핑에 맡겨 성능을 잃는 쪽을 택하지 않았는가
  코드를 짧고 편하게 유지하려고 자동 매핑에 모든 것을 맡기면, 그 편의의 대가를 성능 문제로 치르게 된다.

## 6. JPA 사용 범위 한정

JPA를 쓰는 목적을 명확히 한정한다. 아래 세 가지가 JPA를 쓰는 핵심 목적이다.

점검 항목
* JPA를 다음 세 가지 목적 안에서 쓰는가
  날 것의 SQL을 직접 쓰지 않기 위해, 파라미터 바인딩을 안전하게 처리하기 위해, 타입 안전하게 데이터를 읽기 위해.
* 메모리 객체 자동 분해, 깊은 cascade 저장, 조인 테이블 자동 생성, JSON 자동 매핑 같은 기능은, 만들어지는 DB 구조를 정확히 이해하고 책임질 수 있을 때만 쓰는가
  쓰지 말라는 것이 아니라, 그것이 만들어 내는 DB 구조를 이해하고 책임질 수 있을 때만 쓴다는 것이다.

> DB는 관계형이지 객체지향이 아니다. 객체지향적 환상을 DB에 강요하지 말자.

이 선만 지키면 JPA는 참 좋은 도구다. 선을 넘으면 좋은 도구가 오히려 독이 된다.
