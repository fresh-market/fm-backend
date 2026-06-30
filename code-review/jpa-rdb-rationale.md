# JPA 사용 점검의 근거 (RDB 관점 유지)

이 문서는 [jpa-rdb-guideline.md](./jpa-rdb-guideline.md)의 점검 항목이 근거하는 원문이다.
가이드는 이 문서에서 코드 리뷰에 적용할 점검 항목만 추출해 정리한 것이며, 판단이 애매하거나 배경이 필요할 때 이 문서를 참고한다.

---

## 개요

> ORM은 좋은 도구지만 정해진 용도로만 쓰고 그 선을 넘지 말자! DB는 관계형이지 객체지향이 아니므로, 객체지향적 환상을 DB에 강요하면 안된다.

"ORM을 쓰되 관계형 DB의 관점을 잃지 말자"는 주장을 JPA와 Spring 환경에 맞춰 정리한 것이다.

---
## ORM의 장점과 적절한 사용 범위

### 1. ORM(JPA)이란 무엇이고 무엇이 좋은가?

ORM(Object Relational Mapping)은 객체와 관계형 데이터베이스를 연결해 주는 기술이다. DB의 `member` 테이블과 코드의 `Member` 클래스를 자동으로 매핑해 주므로, `SELECT * FROM member` 같은 SQL을 직접 쓰지 않고 객체를 다루듯 DB를 다룰 수 있다.

```java
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
// SQL을 직접 쓰지 않고 객체로 저장/조회
memberRepository.save(member);
Member found = memberRepository.findById(1L).orElseThrow();
```

JPA가 주는 이점은 분명하다. 다음과 같은 실수를 줄여 준다.

1. **SQL 인젝션 위험 감소**: 파라미터 바인딩을 JPA가 자동으로 처리한다.
2. **타입 안정성**: 컬럼 타입과 객체 필드 타입이 매핑되어 컴파일 시점에 많은 실수가 걸러진다.
3. **기본 CRUD 실수 감소**: 반복적인 INSERT, UPDATE, SELECT 코드를 직접 작성하지 않아도 된다.
4. **마이그레이션 관리**: 스키마 변경을 도구로 추적하고 관리할 수 있다.

특히 코드 우선(Code First) 방식으로 갈 때 JPA는 꽤 괜찮은 선택이다. 여기까지는 ORM의 장점이 명확하다.

### 2. 우려되는 ORM의 부가적인 기술

문제는 그다음이다. ORM의 핵심 기술인 매핑, 변경 추적(Dirty Checking), 지연 로딩(Lazy Loading), 마이그레이션(Migration)은 이미 오래전에 완성되었다. 그래서 요즘 나오는 기능들은 "이것도 된다, 저것도 된다" 식의 부가 기능이 많다.

대표적인 예가 이런 것들이다.

1. 메모리 객체를 통째로 저장하면 알아서 여러 테이블로 쪼개서 넣어 주는 기능
2. JSON 문서를 넣으면 컬럼에 자동 매핑해 주는 기능
3. 다대다 관계의 조인 테이블을 자동으로 생성해 주는 기능

JPA와 Hibernate에도 이런 기능이 있다. 예를 들어 다대다 자동 조인 테이블 생성은 이렇게 동작한다.

```java
@Entity
public class Student {
    @Id @GeneratedValue
    private Long id;

    // 개발자가 조인 테이블을 직접 만들지 않아도 Hibernate가 자동 생성
    @ManyToMany
    private List<Course> courses = new ArrayList<>();
}
```

위 코드는 `student_courses` 같은 조인 테이블을 자동으로 만들어 준다. JSON 컬럼 자동 매핑도 가능하다.

```java
@Entity
public class Product {
    @Id @GeneratedValue
    private Long id;

    // 객체를 JSON 컬럼에 자동으로 직렬화/역직렬화
    @JdbcTypeCode(SqlTypes.JSON)
    private ProductOptions options;
}
```

기술적으로는 팬시하고 편하다. 하지만 던져야 할 질문은 이것이다. "이게 정말 필요한가?" 그리고 이런 기능을 무비판적으로 써서 "어, 되네" 하고 넘어가는 것이 위험하다.

### 3. ORM이 가장 적합할때: 테이블과 엔티티의 1대1 매핑

ORM이 가장 빛나는 순간은 DB 테이블 구조를 그대로 매핑할때다. `member` 테이블에 `Member` 엔티티, `orders` 테이블에 `Order` 엔티티가 일대일로 대응하는 구조다.

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

    // 외래 키를 그대로 반영
    @Column(name = "member_id")
    private Long memberId;
}
```

이 구조에서는 테이블을 보면 엔티티가 보이고, 엔티티를 보면 테이블이 보인다. DB 구조가 코드에 투명하게 드러난다.

반면 조인 테이블 자동 생성 같은 기능은 사용하면 문제가 발생한다. 그 기능 하나 때문에 DB 구조가 눈에 보이지 않기 시작하기 때문이다. 자동 생성된 조인 테이블의 컬럼명, 인덱스, 제약조건이 코드 어디에도 명시되지 않으면, DB를 직접 들여다보기 전까지는 구조를 알 수 없다.

물론 본인이 그 구조를 책임지고 이해하고 있다면 써도 될것이다. 다만 "편해서" 쓰는 것과 "이해하고" 쓰는 것은 분명히 다르다.

---
## ORM의 선을 넘었을 때 생기는 문제와 해결

### 문제: 선을 넘으면 DB 설계 감각 상실

가장 큰 경고는 여기에 있다. 요즘 ORM은 완전한 메모리 객체를 그냥 저장 한 번 하면 알아서 여러 테이블에 나눠 넣고 JSON 컬럼에도 넣어 준다. JPA에서 `cascade`와 연관관계를 깊게 걸어 두면 이런 일이 벌어진다.

```java
@Entity
public class Order {
    @Id @GeneratedValue
    private Long id;

    // 저장 한 번에 연관된 모든 것이 줄줄이 함께 저장됨
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToOne(cascade = CascadeType.ALL)
    private Delivery delivery;
}
```

```java
// save 한 번에 order, order_item 여러 건, delivery까지 전부 INSERT
orderRepository.save(order);
```

위의 경우는 편해 보이지만 그 순간 DB 설계 감각이 사라진다. 이것이 핵심 우려다. 두 패러다임은 사고의 중심이 다르다.

|구분|관계형 DB의 사고|객체 중심의 사고|
|---|---|---|
|설계 중심|정규화, 인덱스, 조인 전략, 쿼리 실행 계획|객체 그래프, 참조, 캡슐화|
|데이터 연결|외래 키와 조인|객체 참조|
|성능의 관건|어떤 쿼리가 어떻게 실행되는가|객체를 얼마나 편하게 다루는가|

객체 중심적 사고로 DB를 다루기 시작하면, 훗날 "왜 이렇게 느려요?"라는 질문이 나온다. 그제서야 SQL 로그를 켜고 실행 계획을 들여다보게 된다. 처음부터 DB 관점으로 설계했다면 겪지 않았을 일을, 객체 추상화에 가려진 채로 뒤늦게 수습하는 셈이다.

MySQL 8.4에서 실행 계획을 확인하는 것은 이런 식이다.
```sql
-- 느려진 쿼리의 실행 계획을 뒤늦게 들여다보는 상황
EXPLAIN ANALYZE
SELECT * FROM orders o
JOIN order_item oi ON oi.order_id = o.id
WHERE o.member_id = 42;
```

이 작업 자체가 나쁜 것은 아니다. 문제는 객체 추상화에 취해 있다가 "사고가 난 뒤에야" 이걸 하게 된다는 점이다.

### 해결: 엔티티는 DB 구조 그대로, 복합 객체는 별도 레이어

그래서 권장 방식은 다음과 같다.

1. JPA 엔티티는 DB 구조를 그대로 반영하기 위하여, 테이블 하나에 엔티티 하나가 대응하도록 단순하게 유지한다.
2. 비즈니스 로직에서 필요한 복합 객체가 있으면 그것은 엔티티가 아니라 별도 레이어(서비스 또는 DTO 조합)에서 만든다. DB에서는 데이터만 읽어 오고, 그 데이터를 조합해서 최종 객체를 만들어 반환한다.

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

화면이나 비즈니스 로직에 필요한 복합 객체는 DTO로 따로 정의한다.
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

서비스 레이어에서 필요한 데이터를 읽어와서 조합한다.
```java
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
        // DB에서 필요한 데이터만 읽어 옴
        Order order = orderRepository.findById(orderId).orElseThrow();
        Member member = memberRepository.findById(order.getMemberId()).orElseThrow();
        List<OrderItemDto> items = orderItemRepository.findItemDtosByOrderId(orderId);

        // 읽어 온 데이터를 조합해 최종 복합 객체로 반환
        return new OrderDetailDto(
                order.getId(),
                member.getName(),
                order.getTotalPrice(),
                items
        );
    }
}
```

복잡한 조회는 DTO로 직접 프로젝션해서 필요한 컬럼만 가져온다.
```java
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    // 필요한 컬럼만 DTO로 직접 조회 (엔티티 전체를 로딩하지 않음)
    @Query("SELECT new com.example.dto.OrderItemDto(oi.name, oi.price, oi.count) " +
           "FROM OrderItem oi WHERE oi.orderId = :orderId")
    List<OrderItemDto> findItemDtosByOrderId(@Param("orderId") Long orderId);
}
```

이 방식의 대가는 솔직히 인정해야 한다. DTO가 많이 생기고, 코드가 길어지고, 손이 더 간다. 하지만 코드를 짧고 편하게 유지하려고 서버 성능을 망가뜨리는 것보다는 낫다. 편의를 위해 자동 매핑에 모든 것을 맡기면, 그 편의의 대가를 성능 문제로 치르게 된다.

### 결론: JPA는 "날 것의 쿼리를 안 쓰기 위한" 도구

정리하면 JPA를 쓰는 목적은 다음 세 가지로 한정하는 것이 좋다.

1. 날 것의 SQL을 직접 쓰지 않기 위해
2. 파라미터 바인딩을 안전하게 처리하기 위해
3. 타입 안전하게 데이터를 읽어 오기 위해

그 이상의 기능, 즉 메모리 객체 자동 분해, 깊은 cascade 저장, 조인 테이블 자동 생성, JSON 자동 매핑 같은 것들은 조심해서 다뤄야 한다. 쓰지 말라는 것이 아니라, 그것이 만들어 내는 DB 구조를 정확히 이해하고 책임질 수 있을 때만 쓰라는 것이다.

가장 중요한 원칙은 이것이다.

> DB는 관계형이지 객체지향이 아니다. 객체지향적 환상을 DB에 강요하지 말자.

이 선만 지키면 JPA는 참 좋은 도구다. 선을 넘으면 좋은 도구가 오히려 독이 된다.
