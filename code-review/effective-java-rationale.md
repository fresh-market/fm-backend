# Effective Java 기반 점검 항목의 근거

이 문서는 [effective-java-guideline.md](./effective-java-guideline.md)의 점검 항목이 왜 필요한지를 구체적인 예시와 함께 설명한다.
각 원칙을 어겼을 때 무엇이 잘못되는지와 왜 그 해법이 효과적인지를 다룬다.
원칙의 출처와 상세한 배경은 Joshua Bloch의 Effective Java(3판)를 참고한다. 이 문서의 설명은 모두 새로 작성한 것이다.

## 1. 객체 생성과 파괴

### 왜 정적 팩터리 메서드를 고려하는가 (아이템 1)
생성자는 이름이 클래스명으로 고정되어, 무엇을 만드는지 의도를 이름으로 드러낼 수 없다.

```java
// 생성자 둘이 같은 시그니처라 무엇을 만드는지 구분이 안 됨 (애초에 둘 다 정의 불가)
// 정적 팩터리는 이름으로 의도를 드러낸다
Order delivery = Order.forDelivery(memberId);
Order pickup   = Order.forPickup(memberId);
```

정적 팩터리는 of, from처럼 의미 있는 이름을 붙일 수 있고, 매번 새 인스턴스를 만들 필요가 없어 캐싱이나 싱글톤 반환도 가능하다.

### 왜 인자가 많으면 빌더를 쓰는가 (아이템 2)
생성자 인자가 많아지면 같은 타입 인자의 순서를 헷갈려 잘못 넘겨도 컴파일은 통과한다.

```java
// 점검 대상: amount 와 quantity 가 둘 다 int 라 순서를 바꿔 넘겨도 컴파일됨
new Order(1L, 2L, 1000, 3, true, false);   // 1000과 3, true와 false 순서를 헷갈리기 쉬움

// 개선: 빌더는 이름으로 넘겨 순서 실수를 구조적으로 막음
Order.builder().memberId(1L).productId(2L).amount(1000).quantity(3).build();
```

### 왜 자원을 직접 만들지 않고 주입받는가 (아이템 5)
클래스가 협력 객체를 내부에서 직접 생성하면 그 구현에 묶여, 테스트에서 대역으로 바꿀 수 없고 구현 교체가 어렵다.

```java
// 점검 대상: 내부에서 직접 생성 → 테스트에서 가짜 사전으로 못 바꿈
public class SpellChecker {
    private final Dictionary dict = new KoreanDictionary();
}

// 개선: 주입받아 결합을 낮추고 교체와 테스트를 쉽게
public SpellChecker(Dictionary dict) { this.dict = dict; }
```

### 왜 불필요한 객체 생성을 피하는가 (아이템 6)
반복문 안에서 같은 불변 객체를 매번 새로 만들면 생성 비용이 누적된다.

```java
// 점검 대상: 반복마다 같은 Pattern 을 새로 컴파일 (비싼 연산)
for (String s : inputs) {
    if (s.matches("[0-9]+")) { ... }   // 내부에서 매번 Pattern 컴파일
}

// 개선: 한 번 컴파일해 재사용
private static final Pattern NUMBER = Pattern.compile("[0-9]+");
for (String s : inputs) { if (NUMBER.matcher(s).matches()) { ... } }
```

### 왜 다 쓴 참조를 정리하는가 (아이템 7)
GC가 메모리를 회수하지만, 컬렉션이나 캐시가 참조를 계속 들고 있으면 그 객체는 회수되지 못한다.

```java
// 점검 대상: 직접 구현한 스택에서 pop 후 참조를 안 비움 → 꺼낸 객체가 회수되지 못함
public Object pop() {
    return elements[--size];   // elements[size] 가 여전히 객체를 참조 (메모리 누수)
}

// 개선: 다 쓴 참조를 null 로
public Object pop() {
    Object result = elements[--size];
    elements[size] = null;
    return result;
}
```

오래 떠 있는 서버에서 이런 누수가 천천히 쌓여 OutOfMemoryError로 터진다.

### 왜 try-with-resources를 쓰는가 (아이템 9)
try-finally로 자원을 닫으면 close 중에 또 예외가 나는 경우 원래 예외가 가려진다.
try-with-resources는 자원을 자동으로 닫고 예외도 올바르게 전달해, 누수와 디버깅 어려움을 함께 해결한다.

```java
try (var in = new FileInputStream(src); var out = new FileOutputStream(dst)) {
    in.transferTo(out);
}   // 예외가 나도 in, out 모두 안전하게 닫힘
```

## 2. 모든 객체의 공통 메서드

### 왜 equals와 hashCode를 함께 재정의하는가 (아이템 10, 11)
HashMap과 HashSet은 hashCode로 버킷을 찾고 equals로 동등성을 확인한다.

```java
// 점검 대상: equals 만 재정의, hashCode 는 그대로 → Map 에서 객체를 못 찾음
Map<Point, String> map = new HashMap<>();
map.put(new Point(1, 2), "a");
map.get(new Point(1, 2));   // null! 논리적으로 같지만 hashCode 가 달라 다른 버킷을 봄

// 개선: 둘을 함께 재정의
@Override public int hashCode() { return Objects.hash(x, y); }
```

이 불일치는 컴파일 오류 없이 조용히 잘못된 동작을 만들어 추적이 어렵다.

### 왜 toString을 재정의하는가 (아이템 12)
기본 toString은 `Order@1b6d3586`처럼 클래스명과 해시값만 보여 줘 디버깅과 로그에 쓸모가 없다.
의미 있는 toString(`Order{id=1, status=PAID}`)은 로그만 봐도 객체 상태를 파악하게 해 문제 추적 시간을 줄인다.

## 3. 클래스와 인터페이스

### 왜 접근 제어자를 최소화하는가 (아이템 15)
공개 범위가 넓으면 외부가 내부 구현에 의존하게 되어, 내부를 바꿀 때 외부까지 깨진다.

```java
// 점검 대상: 내부 컬렉션을 public 으로 노출 → 외부가 직접 수정 가능, 구조 변경도 불가
public List<Order> orders = new ArrayList<>();

// 개선: 좁게 공개하고 필요한 동작만 제공
private final List<Order> orders = new ArrayList<>();
public List<Order> getOrders() { return List.copyOf(orders); }
```

### 왜 가변성을 최소화하는가 (아이템 17)
가변 객체는 언제 누가 상태를 바꿨는지 추적해야 하고, 여러 스레드가 공유하면 동기화까지 신경 써야 한다.
불변 객체는 한 번 만들어지면 변하지 않아 공유해도 안전하고 동작이 예측 가능하다.

### 왜 상속보다 조합을 우선하는가 (아이템 18)
상속은 상위 클래스의 내부 구현에 강하게 묶인다.

```java
// 점검 대상: HashSet 을 상속해 추가 횟수를 세려 했지만, addAll 이 내부에서 add 를 부르면
// 카운트가 중복되어 틀린다 (상위 구현에 의존한 결과)
public class CountingSet<E> extends HashSet<E> { ... }

// 개선: 조합으로 공개 동작에만 의존
public class CountingSet<E> {
    private final Set<E> delegate;
    private int addCount;
    public boolean add(E e) { addCount++; return delegate.add(e); }
}
```

이 결합은 컴파일러가 잡아 주지 않으므로 리뷰에서 봐야 한다.

### 왜 상속을 의도하지 않은 클래스를 닫는가 (아이템 19)
확장을 고려하지 않고 만든 클래스를 누군가 상속하면, 내부 동작에 의존한 하위 클래스가 상위 변경에 깨진다.
final로 닫거나 확장 방법을 명확히 문서화해야 이런 사고를 막는다.

## 4. 제네릭

### 왜 로 타입을 쓰지 않는가 (아이템 26)
로 타입은 컴파일 시점의 타입 검사를 포기하는 것과 같다.

```java
// 점검 대상: 로 타입 → 엉뚱한 타입을 넣어도 컴파일됨
List list = new ArrayList();
list.add("문자열");
Integer n = (Integer) list.get(0);   // 런타임에 ClassCastException

// 개선: 타입 파라미터 명시 → list.add(123) 같은 실수를 컴파일 단계에서 차단
List<Integer> list = new ArrayList<>();
```

### 왜 비검사 경고를 없애는가 (아이템 27)
unchecked 경고는 타입 안전성이 보장되지 않는 지점을 알리는 신호다.
방치하면 진짜 위험한 경고가 잡음에 묻히고, 나중에 ClassCastException으로 이어진다.

### 왜 한정적 와일드카드를 쓰는가 (아이템 31)
와일드카드 없이 타입을 고정하면 호출자가 넘길 수 있는 타입이 좁아져 API가 경직된다.

```java
// 점검 대상: List<Number> 만 받음 → List<Integer> 를 못 넘김
void sumAll(List<Number> list) { ... }

// 개선: 생산자에 extends → 하위 타입 리스트도 받을 수 있음
void sumAll(List<? extends Number> list) { ... }
```

## 5. 열거 타입과 애너테이션

### 왜 int 상수 대신 enum을 쓰는가 (아이템 34)
int 상수는 타입 안전하지 않아 엉뚱한 정수를 넘겨도 컴파일러가 막지 못한다.

```java
// 점검 대상: int 상수 → setStatus(999) 같은 잘못된 값도 통과
public static final int STATUS_PAID = 1;
order.setStatus(999);   // 막을 방법이 없음

// 개선: enum → 정해진 값만 허용하고 이름으로 의미를 드러냄
public enum OrderStatus { PAID, SHIPPED, DELIVERED }
order.setStatus(OrderStatus.PAID);
```

### 왜 @Override를 일관되게 붙이는가 (아이템 40)
재정의하려던 메서드의 시그니처를 실수로 틀리면, 재정의가 아니라 새 메서드를 정의한 것이 된다.

```java
// 점검 대상: equals(Object) 대신 equals(Point) 를 정의 → 재정의가 아닌 오버로딩
public boolean equals(Point p) { ... }   // @Override 가 없어 실수를 못 잡음

// @Override 를 붙이면 컴파일러가 "상위에 이런 메서드 없음" 으로 막아 준다
```

## 6. 람다와 스트림

### 왜 스트림을 남용하지 않는가 (아이템 45)
스트림은 데이터 변환을 간결하게 표현하지만, 모든 반복을 스트림으로 바꾸면 오히려 읽기 어려워진다.
특히 복잡한 분기나 예외 처리가 섞이면 for문이 더 명확하다. 도구의 강점이 살아나는 곳에만 써야 가독성이 올라간다.

### 왜 스트림에서 부수 효과를 피하는가 (아이템 46)
forEach 안에서 외부 상태를 바꾸면 스트림의 선언적 의도가 깨지고 병렬 처리 시 안전하지 않다.

```java
// 점검 대상: forEach 로 외부 리스트를 채움 (부수 효과)
List<String> names = new ArrayList<>();
members.stream().forEach(m -> names.add(m.getName()));

// 개선: collect 로 결과를 모음 (안전하고 의도가 분명)
List<String> names = members.stream().map(Member::getName).collect(Collectors.toList());
```

## 7. 메서드

### 왜 파라미터를 시작 부분에서 검증하는가 (아이템 49)
잘못된 값을 그대로 진행시키면, 한참 뒤의 엉뚱한 지점에서 터져 원인 추적이 어렵다.

```java
// 점검 대상: 검증 없이 진행 → 음수 amount 가 한참 뒤 결제 로직에서 이상하게 터짐
public void charge(int amount) { ...; pg.request(amount); }

// 개선: 입구에서 검증해 발생 지점에서 바로 드러냄
public void charge(int amount) {
    if (amount <= 0) throw new IllegalArgumentException("amount must be positive: " + amount);
    ...
}
```

### 왜 방어적 복사를 하는가 (아이템 50)
외부에서 받은 가변 객체를 그대로 보관하면, 호출자가 나중에 그 객체를 바꿔 내부 불변식이 깨진다.

```java
// 점검 대상: 넘겨받은 Date 를 그대로 보관 → 호출자가 나중에 그 Date 를 바꾸면 기간이 깨짐
public Period(Date start, Date end) { this.start = start; this.end = end; }

// 개선: 복사본을 보관
this.start = new Date(start.getTime());
```

### 왜 null 대신 빈 컬렉션을 반환하는가 (아이템 54)
null을 반환하면 호출자가 매번 null을 확인해야 하고, 빠뜨리면 NPE가 난다.

```java
// 점검 대상: 없을 때 null 반환 → 호출자가 for 돌리면 NPE
public List<Order> findByMember(Long id) { if (none) return null; ... }

// 개선: 빈 컬렉션 반환 → 호출자가 그대로 순회 가능
public List<Order> findByMember(Long id) { if (none) return Collections.emptyList(); ... }
```

## 8. 일반적인 프로그래밍 원칙

### 왜 정확한 계산에 BigDecimal을 쓰는가 (아이템 60)
float와 double은 이진 부동소수점이라 0.1 같은 십진 소수를 정확히 표현하지 못한다.

```java
// 점검 대상: double 로 금액 계산 → 오차 발생
System.out.println(1.03 - 0.42);   // 0.6100000000000001

// 개선: BigDecimal 로 정확하게 (문자열 생성자 사용)
new BigDecimal("1.03").subtract(new BigDecimal("0.42"));   // 0.61
```

금액에 오차가 누적되면 정산이 틀어진다. BigDecimal이나 최소 단위 정수형(원 단위)을 쓴다.

### 왜 오토박싱을 주의하는가 (아이템 61)
기본 타입과 박싱 타입을 섞으면 반복 연산에서 박싱과 언박싱이 일어나 성능이 떨어진다.

```java
// 점검 대상: 합계를 Long 으로 선언 → 반복마다 박싱/언박싱이 일어나 매우 느려짐
Long sum = 0L;
for (long i = 0; i < 1_000_000; i++) sum += i;   // sum 이 매번 새 Long 으로 박싱됨

// 또한 박싱 타입의 == 는 값이 아니라 참조를 비교한다
Integer a = 1000, b = 1000;
System.out.println(a == b);        // false (참조 비교)
System.out.println(a.equals(b));   // true  (값 비교)
```

### 왜 인터페이스 타입으로 참조하는가 (아이템 64)
구현체 타입으로 변수를 선언하면 나중에 다른 구현으로 바꿀 때 선언부까지 모두 고쳐야 한다.

```java
// 점검 대상: 구현체로 선언 → LinkedList 로 바꾸려면 이 선언과 관련 코드를 고쳐야 함
ArrayList<Order> orders = new ArrayList<>();

// 개선: 인터페이스로 선언 → 구현 교체가 선언 한 줄에만 영향
List<Order> orders = new ArrayList<>();
```

## 9. 예외

### 왜 예외를 흐름 제어에 쓰지 않는가 (아이템 69)
예외는 생성 비용이 있고, 정상 흐름을 예외로 처리하면 코드 의도가 흐려진다.

```java
// 점검 대상: 반복 종료를 예외로 처리 (정상 흐름인데 예외 사용)
try {
    int i = 0;
    while (true) { process(arr[i++]); }
} catch (ArrayIndexOutOfBoundsException e) { /* 끝 */ }

// 개선: 정상 흐름은 정상 제어문으로
for (int i = 0; i < arr.length; i++) process(arr[i]);
```

### 왜 추상화 수준에 맞는 예외를 던지는가 (아이템 73)
하위 기술 예외(SQLException 등)를 그대로 위로 올리면, 상위 계층이 하위 구현 세부에 의존하게 된다.

```java
// 점검 대상: SQLException 을 그대로 던짐 → 호출자가 JDBC 세부에 묶이고, ORM 으로 바꾸면 깨짐
public Order find(Long id) throws SQLException { ... }

// 개선: 도메인 예외로 변환
public Order find(Long id) {
    try { ... } catch (SQLException e) { throw new OrderNotFoundException(id, e); }
}
```

### 왜 예외를 무시하지 않는가 (아이템 77)
catch 블록을 비워 두면 문제가 발생해도 겉으로는 정상으로 보인다.
원인을 알리는 신호가 사라져 잘못된 결과만 남고 추적할 단서가 없어진다. 무시가 의도라면 그 이유를 주석으로 남겨야 한다.

## 10. 동시성

### 왜 공유 가변 데이터를 동기화하는가 (아이템 78)
동기화 없이 여러 스레드가 같은 가변 데이터를 읽고 쓰면, 변경이 서로에게 보이지 않거나 중간 상태가 노출된다.
동기화는 변경의 가시성과 연산의 원자성을 함께 보장한다. (구체 예시는 concurrency-rationale.md 참고)

### 왜 스레드보다 실행자를 쓰는가 (아이템 80)
스레드를 직접 만들고 관리하면 생성과 종료, 예외 처리를 모두 직접 다뤄야 해 실수가 잦다.

```java
// 점검 대상: 요청마다 스레드 직접 생성 → 무제한 생성으로 자원 고갈 위험
new Thread(() -> handle(task)).start();

// 개선: Executor 로 풀과 큐를 표준화된 방식으로 관리
ExecutorService pool = Executors.newFixedThreadPool(10);
pool.submit(() -> handle(task));
```

## 11. 직렬화

### 왜 자바 기본 직렬화를 피하는가 (아이템 85)
자바 기본 직렬화는 역직렬화 과정에서 임의 객체를 만들 수 있어 보안 공격 표면이 넓고, 클래스 구조가 바뀌면 호환성이 깨지기 쉽다.
JSON 같은 명시적 형식을 쓰면 어떤 필드가 오가는지 드러나 공격 표면이 줄고 구조 변경에도 유연하다.
