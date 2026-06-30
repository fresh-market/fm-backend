# SOLID 원칙 리뷰 가이드

객체 지향 설계의 다섯 가지 원칙(SOLID)을 코드 리뷰 점검 항목으로 정리한 가이드다.
모든 자바 변경에 적용한다.

설계 일부는 functional.md의 설계 항목과 겹친다.
functional.md가 계층과 도메인 경계를 본다면, 이 문서는 클래스와 인터페이스 수준의 설계 원칙에 초점을 둔다.

## 1. 단일 책임 원칙 (SRP)

하나의 클래스는 하나의 변경 이유만 가져야 한다.

점검 항목
* 클래스가 여러 관심사를 동시에 다루지 않는가
* 클래스를 바꿀 이유가 둘 이상 떠오르지 않는가
* 메서드가 한 가지 일에 집중하는가

근거
한 클래스가 여러 책임을 가지면, 한 가지 요구사항 변경이 무관한 기능까지 건드려 깨뜨릴 위험이 생긴다.
책임이 하나면 변경 영향이 좁아지고 테스트와 재사용이 쉬워진다.

```java
// 점검 대상: 주문 처리와 알림 발송, 보고서 형식까지 한 클래스가 담당
@Service
public class OrderService {
    public void placeOrder(OrderRequest request) { ... }
    public void sendOrderEmail(Order order) { ... }       // 알림 책임
    public String formatOrderReport(Order order) { ... }  // 표현 책임
}

// 개선: 책임별로 분리
@Service
public class OrderService {
    private final OrderNotifier orderNotifier;

    public void placeOrder(OrderRequest request) {
        Order order = ...;
        orderNotifier.notify(order);
    }
}
```

## 2. 개방 폐쇄 원칙 (OCP)

확장에는 열려 있고 변경에는 닫혀 있어야 한다.

점검 항목
* 새로운 종류를 추가할 때 기존 코드를 수정하지 않고 확장으로 대응할 수 있는가
* 타입에 따라 분기하는 switch나 if 사슬이 새 타입마다 늘어나지 않는가
* 변하는 부분을 추상화 뒤로 숨겼는가

근거
타입이 늘 때마다 기존 분기문을 고쳐야 하면, 수정 지점이 흩어져 빠뜨리기 쉽고 회귀 위험이 커진다.
다형성으로 분리하면 새 동작을 추가만 하면 되어 기존 코드를 건드리지 않는다.

```java
// 점검 대상: 등급이 추가될 때마다 분기를 수정해야 함
public int discount(Member member, int price) {
    switch (member.getGrade()) {
        case BRONZE: return price;
        case SILVER: return price * 95 / 100;
        case GOLD:   return price * 90 / 100;
        default:     return price;
    }
}

// 개선: 등급별 정책을 추상화하여 추가만으로 확장
public interface DiscountPolicy {
    int apply(int price);
}

public int discount(Member member, int price) {
    return member.getGrade().getDiscountPolicy().apply(price);
}
```

## 3. 리스코프 치환 원칙 (LSP)

하위 타입은 상위 타입을 대체할 수 있어야 한다.

점검 항목
* 하위 클래스가 상위 타입의 규약을 어기지 않는가
* 상속받은 메서드를 지원하지 않는다며 예외를 던지지 않는가
* 하위 타입이 상위 타입의 기대 동작(반환값, 예외, 불변식)을 좁히지 않는가

근거
하위 타입이 상위 타입 자리에서 다르게 동작하면, 상위 타입을 믿고 작성한 코드가 예상치 못하게 깨진다.
이 위반은 상속을 쓴 곳 어디서든 터질 수 있어 추적이 어렵다.

```java
// 점검 대상: 상속받은 동작을 지원하지 못해 예외를 던짐 (대체 불가)
public class ReadOnlyList<E> extends ArrayList<E> {
    @Override
    public boolean add(E e) {
        throw new UnsupportedOperationException();  // 상위 규약 위반
    }
}

// 개선: 상속 대신 필요한 동작만 노출하는 별도 타입으로 설계
public interface ReadOnlyList<E> {
    E get(int index);
    int size();
}
```

## 4. 인터페이스 분리 원칙 (ISP)

클라이언트는 쓰지 않는 메서드에 의존하도록 강요받으면 안 된다.

점검 항목
* 인터페이스가 너무 커서 구현체가 필요 없는 메서드까지 떠안지 않는가
* 일부 구현체가 특정 메서드를 빈 구현이나 예외로 채우지 않는가
* 역할에 따라 인터페이스를 나눌 수 있는가

근거
큰 인터페이스는 구현체에 불필요한 의무를 지우고, 한 메서드의 변경이 무관한 구현체에까지 영향을 준다.
역할별로 잘게 나누면 각 클라이언트는 자신이 쓰는 것에만 의존한다.

```java
// 점검 대상: 모든 구현체가 모든 기능을 떠안는 큰 인터페이스
public interface UserService {
    User find(Long id);
    void register(UserRequest request);
    void exportToExcel();   // 일부 구현체에는 불필요
}

// 개선: 역할별로 인터페이스 분리
public interface UserReader { User find(Long id); }
public interface UserRegister { void register(UserRequest request); }
```

## 5. 의존성 역전 원칙 (DIP)

고수준 모듈이 저수준 모듈의 구현이 아니라 추상화에 의존해야 한다.

점검 항목
* 비즈니스 로직이 구체 구현체가 아니라 인터페이스에 의존하는가
* 구현체를 직접 생성(new)하지 않고 주입받는가
* Spring에서 생성자 주입으로 협력 객체를 받는가

근거
고수준 로직이 특정 구현에 직접 묶이면, 구현을 바꿀 때 로직까지 수정해야 하고 테스트에서 대역으로 갈아끼우기 어렵다.
추상화에 의존하면 구현 교체와 테스트가 모두 쉬워진다.

```java
// 점검 대상: 비즈니스 로직이 구체 구현을 직접 생성
public class OrderService {
    private final TossPaymentClient paymentClient = new TossPaymentClient();
}

// 개선: 추상화에 의존하고 구현은 주입받음
public class OrderService {
    private final PaymentClient paymentClient;  // 인터페이스

    public OrderService(PaymentClient paymentClient) {
        this.paymentClient = paymentClient;
    }
}
```
