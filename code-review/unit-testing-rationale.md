# 단위 테스트 점검 항목의 근거

이 문서는 [unit-testing-guideline.md](./unit-testing-guideline.md)의 점검 항목이 왜 필요한지를 구체적인 예시와 함께 설명한다.
원칙의 출처와 상세한 배경은 Vladimir Khorikov의 Unit Testing(Manning, 2020)을 참고한다. 이 문서의 설명은 모두 새로 작성한 것이다.

테스트의 목적은 테스트를 많이 작성하는 것이 아니라, 변경을 안전하게 만드는 것이다.
아래 근거는 모두 "어떤 테스트가 변경을 실제로 지켜 주는가"라는 질문으로 모인다.

## 1. 좋은 테스트의 네 기둥

### 왜 네 속성을 균형 있게 봐야 하는가
회귀 방어와 리팩터링 내성은 테스트의 가치를, 빠른 피드백과 유지보수성은 테스트의 비용을 결정한다.
한쪽만 추구하면 균형이 깨진다.

```java
// 회귀 방어는 강하지만 비용이 큰 예: 실제 외부 시스템을 모두 띄워 검증 (느리고 깨지기 쉬움)
// 비용은 낮지만 가치가 없는 예: 검증 없이 호출만 함
@Test
void getName() {
    Member m = new Member("kim");
    m.getName();   // 단순 getter 호출. 아무것도 검증하지 않아 회귀를 못 잡는다.
}
```

좋은 테스트는 의미 있는 로직을 빠르고 안정적으로 검증하는 지점에서 나온다.

### 왜 리팩터링 내성이 특히 중요한가
동작은 그대로인데 내부 구현만 바꿨을 때 테스트가 깨지면, 그것은 진짜 버그가 아니라 거짓 경보다.

```java
// 점검 대상: 내부 호출 횟수를 검증 → 메서드를 둘로 쪼개는 리팩터링만 해도 깨짐
verify(repository, times(1)).save(any());
verify(mapper, times(1)).toEntity(any());
```

거짓 경보가 반복되면 팀은 테스트 실패를 무시하기 시작하고, 결국 진짜 실패도 함께 묻힌다.
테스트를 신뢰할 수 없게 되는 순간 테스트의 가치는 사라지므로, 리팩터링 내성은 가장 먼저 지켜야 할 속성이다.

## 2. 무엇을 검증할 것인가

### 왜 구현이 아니라 관찰 가능한 동작을 검증하는가
테스트가 내부 메서드 호출 순서나 횟수 같은 구현 세부에 묶이면, 동작을 바꾸지 않는 리팩터링에도 테스트가 깨진다.

```java
// 점검 대상: 내부적으로 어떻게 저장하는지를 검증 (구현에 묶임)
orderService.place(request);
verify(orderRepository).save(any());   // save 를 saveAll 로 바꾸면 깨짐

// 개선: 외부에서 관찰 가능한 결과를 검증 (내부를 바꿔도 살아남음)
Order result = orderService.place(request);
assertThat(result.getStatus()).isEqualTo(OrderStatus.PLACED);
```

### 왜 private 메서드를 직접 테스트하지 않는가
private 메서드는 공개 동작을 구현하는 수단일 뿐이다. 공개 API를 통해 검증하면 그 안의 private 로직도 자연히 함께 검증된다.

```java
// 점검 대상: 리플렉션으로 private 메서드를 직접 호출해 테스트 → 구현에 강하게 묶임
Method m = OrderService.class.getDeclaredMethod("calcDiscount", int.class);
m.setAccessible(true);

// 개선: 공개 메서드를 통해 결과로 검증
Order order = orderService.place(requestWithGoldMember());
assertThat(order.getDiscount()).isEqualTo(1000);
```

private 메서드를 따로 테스트하고 싶은 강한 욕구가 든다면, 그 로직이 별도 클래스로 분리되어야 할 책임이라는 신호인 경우가 많다.

## 3. 테스트 구조

### 왜 given, when, then을 구분하는가
세 단계가 뒤섞이면 무엇이 준비이고 무엇이 검증인지 한눈에 들어오지 않는다.

```java
@Test
void 재고가_부족하면_주문에_실패한다() {
    // given: 상황을 준비
    Stock stock = new Stock(0);

    // when, then: 실행과 검증
    assertThatThrownBy(() -> stock.decrease(1))
        .isInstanceOf(OutOfStockException.class);
}
```

구조가 일정하면 테스트를 읽는 사람이 패턴을 빠르게 인식해 이해 비용이 줄어든다.

### 왜 하나의 동작만 검증하는가
한 테스트에 여러 실행과 검증이 섞이면, 실패했을 때 무엇이 깨졌는지 바로 알기 어렵다.

```java
// 점검 대상: 생성, 수정, 삭제를 한 테스트에서 모두 검증 → 무엇이 깨졌는지 이름만으로 모름
@Test
void orderTest() {
    Order o = service.create(...);  assertThat(...);
    service.update(o.getId(), ...); assertThat(...);
    service.delete(o.getId());      assertThat(...);
}
```

하나의 동작에 집중하면 실패 원인이 곧 테스트 이름(`재고가_부족하면_주문에_실패한다`)으로 드러난다.

### 왜 테스트에 로직을 넣지 않는가
테스트 안에 조건 분기나 반복이 들어가면 테스트 자체에 버그가 생길 수 있다.

```java
// 점검 대상: 테스트 안의 if 분기 → 기대값 계산이 틀리면 테스트가 틀린 것을 통과시킴
int expected = member.getGrade() == GOLD ? 900 : 1000;
assertThat(service.price(member)).isEqualTo(expected);

// 개선: 분기 없이 케이스를 나눠 기대값을 직접 명시
assertThat(service.price(goldMember)).isEqualTo(900);
```

검증해야 할 코드를 검증하는 코드가 또 검증을 필요로 하는 모순이 생기므로, 테스트는 단순하고 직선적이어야 한다.

## 4. 테스트 더블

### 왜 stub과 mock을 구분하는가
stub은 테스트에 입력을 제공하는 역할이고, mock은 나가는 호출을 검증하는 역할이다.

```java
// 점검 대상: 입력을 제공하는 stub 과의 상호작용까지 검증 (불필요하게 구현에 묶임)
when(memberReader.find(1L)).thenReturn(member);
orderService.place(request);
verify(memberReader).find(1L);   // 입력 제공자를 verify 할 필요가 없다

// 개선: stub 은 입력만 제공, 검증은 결과로
when(memberReader.find(1L)).thenReturn(member);
Order order = orderService.place(request);
assertThat(order.getMemberId()).isEqualTo(1L);
```

나가는 부수 효과(예: 메일 발송)를 검증할 때만 mock 으로 verify 한다. 이 구분을 지키면 꼭 필요한 것만 검증하게 되어 리팩터링 내성이 유지된다.

### 왜 과도한 모킹을 피하는가
모든 협력 객체를 모킹하면 테스트가 코드의 내부 연결 구조를 그대로 베끼게 된다.
그러면 협력 구조를 바꾸는 모든 리팩터링이 테스트를 깨뜨린다.
모킹은 외부 경계(외부 API 등)에서만 쓰고, 도메인 로직은 가능하면 실제 객체로 동작을 검증하는 편이 견고하다.

## 5. 통합 테스트와 의존성

### 왜 데이터베이스는 실제로 사용하는가
DB는 우리가 관리하고 애플리케이션 외부로 노출되지 않는 의존성이다.

```java
// 점검 대상: DB 를 mock 으로 대체 → 쿼리 오타, 매핑 오류, 제약 위반을 전혀 못 잡음
when(orderRepository.save(any())).thenReturn(order);

// 개선: 실제 DB 로 매핑과 쿼리까지 검증
@DataJpaTest
class OrderRepositoryTest {
    @Autowired OrderRepository orderRepository;

    @Test
    void 주문을_저장하고_조회한다() {
        Order saved = orderRepository.save(new Order(...));
        assertThat(orderRepository.findById(saved.getId())).isPresent();
    }
}
```

DB 연동은 실제로 자주 틀리는 영역이라, mock 으로 대체하면 정작 검증해야 할 부분이 비게 된다.

### 왜 외부 API는 mock으로 대체하는가
외부 결제 API처럼 우리가 통제할 수 없는 공유 의존성은 테스트에서 직접 호출하면 느리고 불안정하며, 외부 상태(점검 시간, 잔액 등)에 결과가 좌우된다.
이런 비관리 의존성만 mock 으로 대체하면, 테스트의 안정성과 속도를 지키면서도 우리 코드의 동작을 검증할 수 있다.

## 6. 테스트 코드 품질

### 왜 테스트 코드를 운영 코드처럼 관리하는가
테스트 코드도 계속 읽히고 유지보수된다.
중복된 셋업이 여러 테스트에 흩어져 있으면, 생성자 시그니처 하나만 바뀌어도 수십 개 테스트를 손봐야 한다.
가독성이 떨어지고 중복이 쌓이면 테스트를 고치는 비용이 커져 결국 테스트를 방치하게 되므로, 공통 셋업은 헬퍼나 픽스처로 모은다.

### 왜 테스트 간 상태 공유를 피하는가
테스트가 공유 상태에 의존하면 실행 순서에 따라 결과가 달라진다.

```java
// 점검 대상: static 공유 상태에 누적 → A 가 먼저 돌면 B 가 실패하는 등 순서 의존
static List<Order> orders = new ArrayList<>();

@Test void a() { orders.add(...); assertThat(orders).hasSize(1); } // 단독은 통과
@Test void b() { assertThat(orders).isEmpty(); }                   // a 뒤에 돌면 실패
```

각 테스트가 자기 상태를 독립적으로 준비해야(`@BeforeEach`로 초기화 등) 결과가 일관된다.

### 왜 커버리지를 목표로 삼지 않는가
커버리지는 코드가 실행됐는지만 알려 줄 뿐, 제대로 검증됐는지는 말해 주지 않는다.

```java
// 이 테스트는 calculate 의 모든 줄을 실행해 커버리지 100% 를 만들지만,
// 반환값을 검증하지 않아 어떤 버그도 잡지 못한다.
@Test
void calculate() {
    service.calculate(input);   // assert 가 없음
}
```

숫자를 목표로 삼으면 이런 의미 없는 테스트가 늘어난다. 커버리지는 참고 지표일 뿐 목적이 아니다.
