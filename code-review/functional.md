# 기능과 코드 품질 리뷰 가이드

모든 PR에 적용되는 보편 규칙이다.
기능 정확성, 가독성, 설계, 예외 처리, 테스트, 컨벤션을 다룬다.

## 1. 기능 정확성

요구사항을 실제로 충족하는지 가장 먼저 확인한다.

점검 항목
* 정상 경로뿐 아니라 예외 경로가 구현되어 있는가
* 경계값(빈 입력, null, 0, 음수, 최대값, 빈 컬렉션)을 처리하는가
* 반환값과 부수 효과가 의도와 일치하는가

```java
// 점검 대상: null과 빈 리스트 분기가 없음
public int sum(List<Integer> numbers) {
    int total = 0;
    for (int n : numbers) {  // numbers가 null이면 NPE
        total += n;
    }
    return total;
}

// 개선
public int sum(List<Integer> numbers) {
    if (numbers == null || numbers.isEmpty()) {
        return 0;
    }
    return numbers.stream().mapToInt(Integer::intValue).sum();
}
```

## 2. 가독성과 유지보수성

다른 사람이 읽었을 때 의도가 바로 파악되는지 본다.

점검 항목
* 변수와 메서드 이름이 역할을 드러내는가
* 메서드가 한 가지 책임만 가지는가
* 중복 코드가 없는가
* 주석이 "무엇을"이 아니라 "왜"를 설명하는가
* 매직 넘버를 상수로 분리했는가

```java
// 의도가 드러나지 않는 이름과 매직 넘버
if (u.getT() > 30) { ... }

// 개선
private static final int MAX_INACTIVE_DAYS = 30;

if (user.getInactiveDays() > MAX_INACTIVE_DAYS) { ... }
```

## 3. 설계와 구조

계층 간 책임이 분리되어 있는지, 도메인 간 경계가 지켜지는지, 설계 원칙을 따르는지 확인한다.
이 프로젝트는 도메인형 구조(package-by-feature)를 사용하므로, 도메인 안의 계층 분리와 도메인 사이의 경계를 함께 본다.

점검 항목
* 단일 책임 원칙을 따르는가
* Controller에 비즈니스 로직이 들어가 있지 않은가
* Service와 Repository의 책임이 섞이지 않았는가
* 외부 의존성이 구현체가 아닌 추상화에 의존하는가
* 한 도메인이 다른 도메인의 내부 클래스(Repository, Entity 등)에 직접 접근하지 않는가
* 도메인 간 의존이 한 방향으로 흐르고 순환 참조가 없는가
* 도메인 사이를 오갈 때 내부 Entity 대신 공개된 인터페이스나 DTO로 주고받는가

```java
// 점검 대상: Controller가 검증, 도메인 생성, 영속성을 모두 수행
@RestController
public class OrderController {
    private final OrderRepository orderRepository;

    @PostMapping("/orders")
    public OrderResponse create(@RequestBody OrderRequest request) {
        if (request.getAmount() <= 0) { ... }
        Order order = new Order(...);
        return new OrderResponse(orderRepository.save(order));
    }
}

// 개선: 비즈니스 로직은 Service로 위임
@RestController
public class OrderController {
    private final OrderService orderService;

    @PostMapping("/orders")
    public OrderResponse create(@RequestBody OrderRequest request) {
        return orderService.create(request);
    }
}
```

```java
// 점검 대상: order 도메인이 user 도메인의 Repository에 직접 접근
@Service
public class OrderService {
    private final UserRepository userRepository;  // 다른 도메인 내부에 침투

    public void create(OrderRequest request) {
        User user = userRepository.findById(request.getUserId()).orElseThrow();
        ...
    }
}

// 개선: user 도메인이 공개한 인터페이스를 통해 협력
@Service
public class OrderService {
    private final UserQueryService userQueryService;  // user 도메인의 공개 API

    public void create(OrderRequest request) {
        UserInfo user = userQueryService.getById(request.getUserId());
        ...
    }
}
```

## 4. 예외 처리

예외를 적절히 처리하고 추적 가능한지 본다.
(트랜잭션 롤백과 관련된 예외 처리는 database.md를 함께 참고한다.)

점검 항목
* 예외를 잡고 아무 처리 없이 넘기지 않는가
* 로그가 적절한 레벨로 남는가(예외 스택 포함 여부)
* 예외를 의미 있는 도메인 예외로 변환하는가
* 자원을 try-with-resources로 안전하게 해제하는가

```java
// 점검 대상: 예외를 삼켜서 원인 추적 불가
try {
    paymentClient.pay(order);
} catch (Exception e) {
    // 아무 처리 없음
}

// 개선
try {
    paymentClient.pay(order);
} catch (PaymentException e) {
    log.error("결제 실패 orderId={}", order.getId(), e);
    throw new OrderProcessingException(order.getId(), e);
}
```

## 5. 일반 성능

알고리즘과 자료구조 수준의 비효율을 본다.
(DB 접근 성능과 N+1은 database.md에서 다룬다.)

점검 항목
* 반복문 안에서 불필요한 연산이 발생하지 않는가
* 조회 패턴에 맞는 자료구조를 쓰는가(List 탐색을 Map으로 대체 등)
* 불필요한 객체 생성이 반복되지 않는가

```java
// 점검 대상: 매 조회마다 리스트 전체 순회 (O(n) 반복)
for (Order order : orders) {
    User user = users.stream()
        .filter(u -> u.getId().equals(order.getUserId()))
        .findFirst().orElseThrow();
}

// 개선: Map으로 한 번 인덱싱 후 O(1) 조회
Map<Long, User> userMap = users.stream()
    .collect(Collectors.toMap(User::getId, Function.identity()));
for (Order order : orders) {
    User user = userMap.get(order.getUserId());
}
```

## 6. 테스트

변경 사항에 대한 검증이 함께 작성되었는지 본다.

점검 항목
* 변경된 로직에 대한 테스트가 있는가
* 정상 케이스뿐 아니라 실패 케이스와 경계값을 검증하는가
* 테스트가 구현 세부사항이 아니라 동작을 검증하는가
* 테스트 이름이 검증 내용을 드러내는가

```java
@Test
void 재고가_부족하면_주문에_실패한다() {
    Product product = new Product("상품", 0);  // 재고 0
    assertThrows(OutOfStockException.class,
        () -> orderService.order(product, 1));
}
```

## 7. 컨벤션 일관성

팀의 코딩 스타일과 네이밍 규칙을 따르는지 본다.
이 항목은 포맷터와 정적 분석 도구(Checkstyle, SpotBugs)로 자동화하고,
리뷰어는 위의 본질적 항목에 집중한다.
