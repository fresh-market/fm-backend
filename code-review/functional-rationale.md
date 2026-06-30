# 기능과 코드 품질 점검 항목의 근거

이 문서는 [functional.md](./functional.md)의 각 점검 항목이 왜 필요한지를 구체적인 예시와 함께 설명한다.
리뷰어가 규칙을 기계적으로 적용하지 않고, 무엇을 막으려는 규칙인지 이해해서 판단하도록 돕는다.

## 1. 기능 정확성

### 정상 경로뿐 아니라 예외 경로를 구현했는가
대부분의 버그는 정상 흐름이 아니라 예외 흐름에서 발생한다.

```java
// 점검 대상: 결제 성공 경로만 처리, 실패 시 주문은 이미 생성된 채로 남음
public void order(OrderRequest req) {
    Order order = orderRepository.save(new Order(req));
    paymentClient.pay(order);   // 실패하면? 주문만 남고 결제는 안 된 불일치 상태
}
```

개발자는 만들면서 정상 시나리오를 먼저 떠올리기 때문에 예외 경로는 자연스럽게 비어 있게 된다.
리뷰는 작성자가 생각하지 못한 흐름을 외부 시선으로 채워 넣는 과정이고, 예외 경로 점검이 그 핵심이다.

### 경계값을 처리하는가
오류는 값의 양 끝(0, 최대값, 빈 컬렉션, null)에서 집중적으로 발생한다.

```java
// 점검 대상: 빈 리스트면 0으로 나눠 ArithmeticException, null이면 NPE
public int average(List<Integer> scores) {
    return scores.stream().mapToInt(i -> i).sum() / scores.size();
}
```

중간값은 대충 짜도 동작하지만 경계에서 깨지므로, 경계값을 따로 의식해서 보지 않으면 놓친다.
null 미처리로 인한 NullPointerException은 운영 장애의 흔한 원인이다.

### 반환값과 부수 효과가 의도와 일치하는가
메서드가 반환만 하는지, 아니면 상태까지 바꾸는지 명확하지 않으면 호출하는 쪽에서 잘못 쓴다.

```java
// 점검 대상: 이름은 조회처럼 보이는데 내부에서 상태를 바꿈
public Member getMember(Long id) {
    Member m = memberRepository.findById(id).orElseThrow();
    m.setLastAccessedAt(now());   // 조회인 줄 알고 부른 호출자가 예측 못 하는 부수 효과
    return m;
}
```

의도하지 않은 부수 효과는 호출자가 예측할 수 없어 추적이 어려운 버그로 이어진다.

## 2. 가독성과 유지보수성

### 이름이 역할을 드러내는가
코드는 작성하는 시간보다 읽히는 시간이 훨씬 길다.

```java
// 점검 대상: 이름이 의도를 숨김
if (u.getT() > 30) { ... }   // T가 무엇인지, 30이 무엇인지 알 수 없음

// 개선: 이름이 곧 설명
if (user.getInactiveDays() > MAX_INACTIVE_DAYS) { ... }
```

이름이 역할을 설명하면 주석 없이도 의도가 전달되고, 잘못 지은 이름은 읽는 사람을 매번 오해시킨다. 이름은 가장 많이 읽히는 문서다.

### 메서드가 한 가지 책임만 가지는가
여러 일을 하는 메서드는 한 군데를 고치면 다른 기능이 깨질 위험이 있다.
책임이 하나면 변경 영향 범위가 좁아지고, 테스트와 재사용이 쉬워진다.

### 중복 코드가 없는가
같은 로직이 여러 곳에 있으면, 고칠 때 한 군데를 빠뜨려 일부만 수정되는 버그가 생긴다.

```java
// 세 곳에 같은 할인 계산이 복사되어 있으면, 할인율이 바뀔 때 한 곳을 빠뜨려
// 화면마다 다른 금액이 표시되는 버그가 생긴다.
int price = origin - origin * 10 / 100;   // A 화면
int price = origin - origin * 10 / 100;   // B 화면 (여기만 안 고치면 불일치)
```

중복 제거는 단순히 줄 수를 줄이는 것이 아니라 변경 지점을 하나로 모으는 일이다.

### 주석이 "왜"를 설명하는가
"무엇을" 하는지는 코드 자체가 이미 말한다. 코드를 봐도 알 수 없는 것은 그런 선택을 한 이유다.

```java
// 나쁜 주석: 코드가 이미 말하는 것을 반복 (코드가 바뀌면 거짓말이 됨)
count++; // count를 1 증가시킨다

// 좋은 주석: 코드만으로 알 수 없는 이유를 설명
// 외부 결제사가 0.5초 내 중복 요청을 거부하므로 최소 간격을 둔다
Thread.sleep(500);
```

### 매직 넘버를 상수로 분리했는가
숫자 30이 무엇을 의미하는지 코드만 봐서는 알 수 없고, 같은 의미의 값이 여러 곳에 흩어지면 일괄 변경이 어렵다.

```java
private static final int MAX_INACTIVE_DAYS = 30;
if (user.getInactiveDays() > MAX_INACTIVE_DAYS) { ... }
```

상수로 이름을 붙이면 의미가 드러나고 변경 지점이 하나로 모인다.

## 3. 설계와 구조

### 단일 책임 원칙을 따르는가
하나의 클래스가 여러 변경 이유를 가지면, 한 가지 요구사항 변경이 무관한 코드까지 건드리게 된다.
책임이 분리되어 있으면 변경이 국소화되어 안전하다. (구체 예시는 solid-principles-rationale.md의 SRP 참고)

### Controller에 비즈니스 로직이 없는가
Controller에 로직이 들어가면 HTTP 요청 없이는 그 로직을 테스트할 수 없고, 다른 진입점에서 재사용할 수 없다.

```java
// 점검 대상: 검증과 도메인 생성, 영속성까지 Controller가 직접 수행
@PostMapping("/orders")
public OrderResponse create(@RequestBody OrderRequest req) {
    if (req.getAmount() <= 0) { ... }            // 이 규칙을 배치에서 재사용 못 함
    Order order = new Order(...);
    return new OrderResponse(orderRepository.save(order));
}
```

로직을 Service에 두면 HTTP 진입 없이 단위 테스트할 수 있고, 배치나 메시지 소비자에서도 같은 로직을 재사용할 수 있다.

### Service와 Repository의 책임이 섞이지 않았는가
영속성 세부사항이 비즈니스 로직에 섞이면, DB나 ORM을 바꿀 때 비즈니스 코드까지 영향받는다.
계층을 나누면 각 계층을 독립적으로 변경하고 교체할 수 있다.

### 추상화에 의존하는가
구현체에 직접 의존하면 테스트 시 실제 구현을 대체하기 어렵고, 구현 교체가 연쇄 변경을 부른다.
추상화에 의존하면 테스트에서 대역(mock, fake)으로 갈아끼울 수 있고 결합도가 낮아진다. (구체 예시는 solid-principles-rationale.md의 DIP 참고)

### 다른 도메인의 내부 클래스에 직접 접근하지 않는가
도메인형 구조의 핵심은 각 도메인이 독립적으로 변경 가능한 단위라는 점이다.

```java
// 점검 대상: order 도메인이 user 도메인의 Repository에 직접 침투
@Service
public class OrderService {
    private final UserRepository userRepository;   // user 내부 구현에 의존
    // user 의 테이블/엔티티가 바뀌면 order 까지 깨진다
}

// 개선: user 가 공개한 인터페이스로 협력
private final UserQueryService userQueryService;   // user 의 공개 API
```

도메인을 나눈 이점이 사라지고 사실상 하나로 얽힌 코드가 되는 것을 막는다.

### 도메인 간 의존이 한 방향으로 흐르는가
두 도메인이 서로를 참조하면 한쪽만 떼어내 변경하거나 테스트할 수 없고, 빌드와 이해의 단위가 뒤엉킨다.
의존이 한 방향으로 흐르면 어느 도메인이 어느 도메인에 기대는지 명확해져 변경 영향 범위를 예측할 수 있다.
순환 참조는 이 예측 가능성을 무너뜨리므로 특히 경계해야 한다.

### 내부 Entity 대신 공개 인터페이스나 DTO로 주고받는가
도메인의 Entity를 다른 도메인에 그대로 넘기면, 받는 쪽이 그 Entity의 모든 필드와 연관에 의존하게 된다.
이렇게 되면 Entity 구조를 바꾸기 어려워지고 도메인 경계가 흐려진다.
필요한 데이터만 담은 DTO나 공개 인터페이스로 협력하면, 내부 구조를 숨기고 경계를 명확히 유지할 수 있다.

## 4. 예외 처리

### 예외를 삼키지 않는가
잡은 예외를 무시하면 문제가 발생해도 겉으로는 정상으로 보인다.

```java
// 점검 대상: 예외를 잡고 아무것도 안 함 → 결제 실패가 조용히 묻힘
try {
    paymentClient.pay(order);
} catch (Exception e) {
    // 아무 처리 없음. 실패해도 정상처럼 진행되고 로그조차 없다.
}
```

운영 장애에서 가장 곤란한 상황은 무엇이 잘못됐는지 로그조차 없는 경우다.

### 로그가 적절한 레벨로, 스택과 함께 남는가
예외 스택 없이 메시지만 남기면 어디서 발생했는지 알 수 없다.

```java
// 점검 대상: 스택을 버리고 메시지만 남김 → 어느 줄에서 났는지 모름
catch (PaymentException e) {
    log.error("결제 실패: " + e.getMessage());   // e 를 안 넘김
}

// 개선: 예외 객체를 함께 넘겨 스택을 남김
catch (PaymentException e) {
    log.error("결제 실패 orderId={}", order.getId(), e);
}
```

### 의미 있는 도메인 예외로 변환하는가
하위 기술 예외(SQLException 등)를 그대로 위로 던지면, 상위 계층이 기술 세부사항에 의존하게 된다.
도메인 예외로 변환하면 호출자가 비즈니스 관점에서 처리할 수 있다.

### 자원을 안전하게 해제하는가
파일, 커넥션, 스트림을 닫지 않으면 자원이 누수되어 시간이 지나며 고갈된다.

```java
// 개선: try-with-resources 는 예외가 나도 close 를 보장
try (var reader = Files.newBufferedReader(path)) {
    return reader.readLine();
}
```

## 5. 일반 성능

### 반복문 안에서 불필요한 연산이 없는가
반복 횟수가 늘어날수록 안쪽의 작은 비효율이 곱으로 커진다.
데이터가 적을 때는 드러나지 않다가 운영 데이터 규모에서 갑자기 문제가 된다.

### 조회 패턴에 맞는 자료구조를 쓰는가
리스트에서 반복 탐색하면 데이터가 많아질수록 느려진다.

```java
// 점검 대상: 주문마다 회원 리스트 전체를 순회 → 주문 N, 회원 M 이면 N*M 연산
for (Order order : orders) {
    User user = users.stream()
        .filter(u -> u.getId().equals(order.getUserId()))
        .findFirst().orElseThrow();
}

// 개선: Map 으로 한 번 인덱싱 후 O(1) 조회
Map<Long, User> userMap = users.stream()
    .collect(Collectors.toMap(User::getId, Function.identity()));
for (Order order : orders) {
    User user = userMap.get(order.getUserId());
}
```

잘못된 자료구조 선택은 알고리즘 복잡도를 통째로 바꾼다.

## 6. 테스트

### 변경 로직에 테스트가 있는가
테스트가 없으면 지금 동작하는지 확인할 길이 없고, 나중에 안전하게 고칠 수도 없다.
테스트는 미래의 변경을 위한 안전망이다. (테스트 설계의 구체 근거는 unit-testing-rationale.md 참고)

### 실패 케이스와 경계값을 검증하는가
정상 케이스만 검증하는 테스트는 정작 버그가 잘 생기는 영역을 비워 둔다.

```java
@Test
void 재고가_부족하면_주문에_실패한다() {   // 실패 경로를 검증
    Product product = new Product("상품", 0);
    assertThrows(OutOfStockException.class, () -> orderService.order(product, 1));
}
```

### 구현이 아니라 동작을 검증하는가
내부 구현에 묶인 테스트는 리팩터링만 해도 깨져서 안전망이 아니라 짐이 된다.
동작을 검증하면 내부를 바꿔도 테스트가 살아남아 리팩터링을 뒷받침한다.

### 테스트 이름이 검증 내용을 드러내는가
테스트가 깨졌을 때 이름만 보고 무엇이 잘못됐는지 알 수 있어야 한다.
이름이 명확하면 테스트 목록 자체가 명세서 역할을 한다.

## 7. 컨벤션 일관성

### 왜 자동화에 맡기는가
스타일 일관성은 중요하지만, 사람이 공백과 줄바꿈을 지적하는 것은 비용이 크고 감정 소모도 크다.
포맷터와 정적 분석 도구(Checkstyle, SpotBugs)가 기계적으로 처리하면, 사람은 도구가 못 잡는 설계와 로직에 집중할 수 있다.
리뷰의 시간은 한정 자원이므로 기계가 할 일과 사람이 할 일을 나누는 것이 핵심이다.
