# 동시성 점검 항목의 근거

이 문서는 [concurrency.md](./concurrency.md)의 각 점검 항목이 왜 필요한지를 구체적인 예시와 함께 설명한다.
동시성 버그는 재현이 어렵고 특정 부하에서만 드물게 나타나서, 코드 리뷰 단계에서 구조적으로 막는 것이 가장 효과적이다.

## 1. 싱글톤 빈의 가변 상태

### 왜 싱글톤 빈의 인스턴스 필드가 위험한가
Spring 빈은 기본적으로 싱글톤이라, 애플리케이션 전체에서 하나의 인스턴스를 모든 요청이 공유한다.
웹 요청은 여러 스레드로 동시에 처리되므로, 빈의 인스턴스 필드를 변경하면 여러 스레드가 같은 변수를 동시에 건드리게 된다.

예를 들어 아래 코드는 현재 처리 중인 사용자를 인스턴스 필드에 담는다.

```java
@Service
public class InvoiceService {
    private Long currentUserId;   // 모든 요청이 공유하는 단 하나의 필드

    public Invoice issue(Long userId) {
        this.currentUserId = userId;        // (1) 사용자 A가 자기 id를 저장
        applyDiscount();                    // (2) 그사이 사용자 B의 요청이 (1)을 덮어씀
        return new Invoice(this.currentUserId); // (3) A의 청구서에 B의 id가 박힘
    }
}
```

사용자 A의 요청이 (1)과 (3) 사이에 있을 때 사용자 B의 요청이 (1)을 실행하면, A의 청구서에 B의 id가 들어간다.
이런 버그는 동시 요청이 겹칠 때만 드물게 나타나 재현과 추적이 매우 어렵다.

### 왜 상태를 지역 변수로 처리하는가
메서드 지역 변수는 호출하는 스레드마다 스택에 따로 생긴다. 따라서 공유되지 않고 동시성 문제 자체가 발생하지 않는다.

```java
@Service
public class InvoiceService {
    public Invoice issue(Long userId) {
        Invoice invoice = new Invoice(userId);  // 지역 변수: 스레드마다 독립
        applyDiscount(invoice);
        return invoice;
    }
}
```

상태를 빈 밖으로 빼는 것이 가장 단순하고 확실한 해법이다.

### 왜 누적 상태에 원자적 연산을 쓰는가
`count++`는 한 줄처럼 보이지만 실제로는 읽고(read), 더하고(add), 쓰는(write) 세 단계로 나뉜다.
두 스레드가 동시에 같은 값을 읽으면 한쪽의 증가가 사라진다.

```java
// 두 스레드가 모두 count=10을 읽고 11을 쓰면, 결과는 12가 아니라 11이 된다 (갱신 손실)
private int count = 0;
public void increment() { count++; }

// AtomicInteger는 읽고 더하고 쓰는 과정을 쪼개질 수 없는 하나의 연산으로 보장한다
private final AtomicInteger count = new AtomicInteger();
public void increment() { count.incrementAndGet(); }
```

## 2. 공유 자원 접근

### 왜 동시성 안전 컬렉션을 쓰는가
일반 HashMap은 여러 스레드가 동시에 수정하면 내부 버킷 구조가 깨질 수 있다.
특히 리사이즈(rehash) 도중 동시 수정이 겹치면, 과거 자바 버전에서는 내부 링크가 꼬여 조회가 무한 루프에 빠지며 CPU를 100%로 점유하는 사고가 알려져 있다.

```java
// 점검 대상: 여러 스레드가 동시에 put 하면 내부 구조가 손상될 수 있음
private final Map<String, Session> sessions = new HashMap<>();

// 개선: 동시 접근을 견디도록 설계된 구현체 사용
private final Map<String, Session> sessions = new ConcurrentHashMap<>();
```

### 왜 복합 연산을 원자적으로 보호하는가
"있는지 확인하고 없으면 넣는다" 같은 복합 연산은 각 단계가 스레드 안전해도 그 사이에 다른 스레드가 끼어들 수 있다.

```java
// 점검 대상: containsKey 확인과 put 사이에 다른 스레드가 끼어들어 둘 다 put 함
if (!cache.containsKey(key)) {   // 스레드 A, B 모두 false를 봄
    cache.put(key, compute());   // 둘 다 put 하여 compute가 두 번 실행됨
}

// 개선: 확인과 삽입을 하나의 원자 연산으로 묶음
cache.computeIfAbsent(key, k -> compute());
```

### 왜 가변 객체 공유를 피하는가
여러 스레드가 같은 가변 객체를 들고 각자 바꾸면, 변경이 서로 덮어써져 예측 불가능한 상태가 된다.

```java
// 점검 대상: 공유되는 가변 객체. 한 스레드의 setStatus가 다른 스레드의 값을 덮어씀
public class OrderContext { private String status; /* setter */ }

// 개선: 불변 객체로 만들어 공유해도 변경 충돌이 생기지 않게 함
public final class OrderContext {
    private final String status;
    public OrderContext(String status) { this.status = status; }
    public String getStatus() { return status; }
}
```

## 3. 데이터 동시 수정

### 왜 갱신 손실을 막아야 하는가
조회한 뒤 수정하는 흐름에서, 두 요청이 같은 값을 읽고 각자 바꿔 저장하면 나중 저장이 앞선 저장을 덮어쓴다.

```java
// 재고 10에서 두 요청이 동시에 1씩 차감하면, 결과는 8이 아니라 9가 되어 1개가 증발한다
Stock stock = stockRepository.findById(id).orElseThrow(); // 둘 다 10을 읽음
stock.setQuantity(stock.getQuantity() - 1);               // 둘 다 9를 계산
stockRepository.save(stock);                              // 나중 저장이 앞을 덮어씀
```

재고 차감이나 잔액 변경처럼 정합성이 중요한 데이터에서 이 손실이 발생하면 직접적인 사고로 이어진다.

### 왜 낙관적 또는 비관적 잠금을 쓰는가
낙관적 잠금은 충돌이 드물다고 보고, 저장 시점에 버전이 그사이 바뀌었는지 확인해 충돌이면 실패시킨다.

```java
@Entity
public class Stock {
    @Version
    private Long version;   // 저장 시 버전이 읽을 때와 다르면 예외를 던져 갱신 손실을 막음
}
```

비관적 잠금은 처음부터 행을 잠가 다른 트랜잭션의 접근을 막는다(예: `SELECT ... FOR UPDATE`).
충돌이 잦으면 비관적 잠금이, 드물면 낙관적 잠금이 유리하다. 어느 쪽이든 무방비로 두는 것보다 안전하다.

## 4. 비동기와 스케줄러

### 왜 내부 호출 시 @Async가 동작하지 않는가
`@Async`는 Spring이 빈을 감싼 프록시를 통해 동작한다.
같은 클래스 안에서 자기 메서드를 직접 호출하면 프록시를 거치지 않아 비동기가 적용되지 않고, 기대와 다르게 동기로 실행된다.

```java
@Service
public class ReportService {
    public void run() {
        generate();   // 점검 대상: 내부 호출이라 프록시를 우회 → 동기 실행됨
    }

    @Async
    public void generate() { ... }   // 별도 빈에서 호출해야 비동기로 동작
}
```

이 함정은 코드만 봐서는 드러나지 않으므로 리뷰에서 의식적으로 확인해야 한다.

### 왜 @Async 예외를 따로 처리하는가
별도 스레드에서 실행되는 비동기 작업의 예외는 호출한 쪽으로 전파되지 않는다.

```java
@Async
public void sendEmails() {
    throw new MailException(...);   // 호출자에게 전달되지 않고 스레드 안에서 사라짐
}
```

반환 타입이 void면 예외가 조용히 묻혀 실패 사실조차 알 수 없다.
`CompletableFuture`를 반환하거나 `AsyncUncaughtExceptionHandler`를 등록해 실패를 드러내야 한다.

### 왜 스케줄러 중복 실행을 막는가
서버를 여러 대로 운영하면 같은 `@Scheduled` 작업이 인스턴스마다 동시에 실행된다.

```java
@Scheduled(cron = "0 0 * * * *")
public void settle() {
    // 3대 운영 시 같은 시각에 3번 실행되어 정산이 세 번 일어남
}
```

중복 실행을 막지 않으면 정산이나 알림 발송 같은 작업이 여러 번 일어나 데이터가 중복되거나 어긋난다.
분산 락(예: DB나 Redis 기반)이나 단일 실행을 보장하는 스케줄러로 막아야 한다.

### 왜 비동기와 영속성 컨텍스트의 경계를 보는가
영속성 컨텍스트와 트랜잭션은 스레드에 묶여 있다.
비동기 작업이 그 경계를 넘어가면, 지연 로딩 대상에 접근할 때 컨텍스트가 이미 닫혀 있어 예외가 난다.

```java
@Async
public void process(Order order) {
    order.getItems().size();   // LazyInitializationException: 컨텍스트가 이미 닫힘
}
```

경계를 넘는 데이터는 미리 로딩(fetch join)하거나, 엔티티 대신 필요한 값만 추려 넘기는 식으로 다뤄야 한다.
