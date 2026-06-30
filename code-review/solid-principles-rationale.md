# SOLID 원칙 점검 항목의 근거

이 문서는 [solid-principles.md](./solid-principles.md)의 점검 항목이 왜 필요한지를 구체적인 예시와 함께 설명한다.
각 원칙이 무엇을 막으려는 것인지, 어겼을 때 어떤 비용이 생기는지를 다룬다.

SOLID는 모두 변경 비용을 줄이기 위한 원칙이다.
소프트웨어에서 가장 비싼 것은 변경이고, 다섯 원칙은 변경이 한곳에 모이고 영향이 좁아지도록 설계를 유도한다.

## 1. 단일 책임 원칙 (SRP)

### 왜 한 클래스에 하나의 책임만 두는가
한 클래스가 여러 책임을 가지면, 그 책임들은 각각 다른 이유로 변경된다.

```java
// 점검 대상: 주문 처리, 알림 발송, 보고서 형식을 한 클래스가 담당
@Service
public class OrderService {
    public void placeOrder(OrderRequest req) { ... }       // (a) 주문 규칙이 바뀌면 수정
    public void sendOrderEmail(Order order) { ... }        // (b) 알림 채널이 바뀌면 수정
    public String formatOrderReport(Order order) { ... }   // (c) 보고서 양식이 바뀌면 수정
}
```

이 클래스는 세 가지 서로 다른 이유로 바뀐다. 알림 방식을 손보다가 주문 규칙 코드를 건드려 결제 로직을 깨뜨릴 수 있고, 세 관심사가 한 파일에 몰려 동시 작업 시 머지 충돌이 잦아진다.

```java
// 개선: 책임별로 분리하면 각자 한 가지 이유로만 변경됨
@Service
public class OrderService {            // 주문 규칙만
    private final OrderNotifier notifier;
    public void placeOrder(OrderRequest req) { ...; notifier.notify(order); }
}
// OrderNotifier(알림), OrderReportFormatter(보고서)는 별도 클래스
```

### 어겼을 때의 비용
책임이 얽힌 클래스는 점점 커지면서 이해하기 어려워지고, 테스트도 주문 로직을 검증하려는데 메일 발송까지 모킹해야 하는 식으로 복잡해진다.
책임을 나누면 각 클래스가 작고 명확해져 변경과 테스트가 모두 단순해진다.

## 2. 개방 폐쇄 원칙 (OCP)

### 왜 확장에 열고 변경에 닫는가
요구사항은 늘 새 종류가 추가되는 방향으로 자란다(새 등급, 새 결제 수단 등).

```java
// 점검 대상: 등급이 추가될 때마다 이 분기문을 찾아 수정해야 함
public int discount(Member member, int price) {
    switch (member.getGrade()) {
        case BRONZE: return price;
        case SILVER: return price * 95 / 100;
        case GOLD:   return price * 90 / 100;
        // PLATINUM 등급 추가 시 여기를 고쳐야 하고, 같은 switch 가 코드 곳곳에 흩어져 있으면
        // 한 곳을 빠뜨려 등급별로 다르게 동작하는 버그가 생긴다.
    }
    return price;
}
```

### 왜 다형성으로 푸는가
변하는 부분을 추상화 뒤로 숨기면, 새 종류는 새 구현을 추가하는 것으로 끝나고 기존 코드는 건드리지 않는다.

```java
// 개선: 등급별 정책을 추상화 → 새 등급은 새 구현 추가로 끝남
public interface DiscountPolicy { int apply(int price); }

public int discount(Member member, int price) {
    return member.getGrade().getDiscountPolicy().apply(price);
}
// PLATINUM 추가 시 PlatinumDiscountPolicy 만 만들면 되고, 검증된 기존 코드는 그대로 둔다.
```

검증된 기존 코드를 그대로 두는 것이 회귀를 막는 가장 확실한 방법이다.

## 3. 리스코프 치환 원칙 (LSP)

### 왜 하위 타입이 상위 타입을 대체할 수 있어야 하는가
다형성은 상위 타입을 믿고 코드를 작성하는 것을 전제로 한다.
하위 타입이 상위 타입의 규약을 어기면, 상위 타입 자리에 하위 타입이 들어왔을 때 그 코드가 깨진다.

```java
// 점검 대상: List 를 상속했지만 add 를 지원하지 못해 예외를 던짐
public class ReadOnlyList<E> extends ArrayList<E> {
    @Override public boolean add(E e) { throw new UnsupportedOperationException(); }
}

// List 를 받는 어떤 코드든 ReadOnlyList 가 들어오면 깨진다
void fill(List<String> list) { list.add("x"); }  // 여기서 갑자기 예외
```

상속받은 메서드를 지원하지 못해 예외를 던지는 것이 대표적인 위반이다.

### 어겼을 때의 비용
이 위반은 `List`를 쓴 어느 호출 지점에서든 터질 수 있어 추적이 어렵다.
또한 대체 가능성이 깨지면 호출자가 `if (list instanceof ReadOnlyList)`처럼 타입을 확인하는 분기를 넣게 되어, 다형성의 이점이 사라진다.
대체할 수 없는 관계라면 상속이 아니라 별도 타입(예: 별도의 읽기 전용 인터페이스)으로 설계하는 것이 옳다.

## 4. 인터페이스 분리 원칙 (ISP)

### 왜 큰 인터페이스를 나누는가
인터페이스가 크면 구현체는 자신이 쓰지 않는 메서드까지 구현해야 한다.

```java
// 점검 대상: 모든 구현체가 모든 기능을 떠안는 큰 인터페이스
public interface UserService {
    User find(Long id);
    void register(UserRequest req);
    void exportToExcel();   // 조회만 필요한 구현체도 이걸 떠안아야 함
}

// 엑셀 기능이 필요 없는 구현체가 빈 구현이나 예외로 채움 → LSP 위반으로 번짐
public class ReadOnlyUserService implements UserService {
    public void exportToExcel() { throw new UnsupportedOperationException(); }
}
```

### 어겼을 때의 비용
큰 인터페이스의 `exportToExcel`이 바뀌면, 그 메서드를 쓰지 않는 구현체까지 재컴파일과 수정 대상이 되어 변경이 무관한 곳으로 번진다.

```java
// 개선: 역할별로 인터페이스 분리
public interface UserReader { User find(Long id); }
public interface UserRegister { void register(UserRequest req); }
// 각 클라이언트는 자신이 실제로 쓰는 인터페이스에만 의존한다.
```

## 5. 의존성 역전 원칙 (DIP)

### 왜 추상화에 의존하는가
고수준 비즈니스 로직이 저수준 구현에 직접 의존하면, 구현이 바뀔 때마다 비즈니스 로직까지 수정해야 한다.

```java
// 점검 대상: 주문 로직이 특정 결제사 구현에 직접 묶임
public class OrderService {
    private final TossPaymentClient paymentClient = new TossPaymentClient();
    // 결제사를 KakaoPay 로 바꾸면 이 핵심 로직 클래스를 수정해야 한다.
}
```

변하기 쉬운 세부 구현에 변하지 않아야 할 핵심 로직이 묶이는 셈이다.

### 왜 구현을 주입받는가
클래스 안에서 구현체를 직접 생성하면 그 구현에 고정되어, 테스트에서 대역으로 바꿀 수 없다.

```java
// 개선: 인터페이스에 의존하고 구현을 주입
public class OrderService {
    private final PaymentClient paymentClient;       // 추상화
    public OrderService(PaymentClient paymentClient) { this.paymentClient = paymentClient; }
}
// 운영에서는 TossPaymentClient, 테스트에서는 FakePaymentClient 를 끼울 수 있다.
```

Spring의 생성자 주입은 이 원칙을 자연스럽게 따르도록 돕는다.

## 원칙 간의 관계

다섯 원칙은 따로 있는 규칙이 아니라 서로 맞물린다.
SRP로 책임을 나누면 작은 단위가 생기고, ISP는 그 단위를 인터페이스 수준에서 다시 좁힌다.
DIP로 추상화에 의존하게 만들면, OCP가 요구하는 확장(기존 코드 수정 없이 구현 추가)이 가능해진다.
그리고 LSP는 그 확장으로 끼워 넣는 구현이 상위 타입의 약속을 지키도록 보장한다.

앞의 OCP 할인 예시가 이 맞물림을 잘 보여준다.
DiscountPolicy라는 추상화에 의존(DIP)하기 때문에 새 등급을 구현 추가만으로 확장(OCP)할 수 있고, 각 정책이 apply 하나만 책임지며(SRP, ISP), 모든 정책이 "가격을 받아 할인가를 반환한다"는 약속을 지키므로(LSP) 호출부가 정책을 구분하지 않고 쓸 수 있다.
한 원칙을 잘 지키면 다른 원칙도 따라오기 쉬워진다.
