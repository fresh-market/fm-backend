# 동시성 리뷰 가이드

공유 상태를 가진 Service, 비동기 및 스케줄러 코드, 동시 수정 가능성이 있는 데이터 처리에 적용한다.

## 1. 싱글톤 빈의 가변 상태

Spring 빈은 기본이 싱글톤이므로, 인스턴스 필드에 가변 상태를 두면 여러 스레드가 공유한다.

점검 항목
* 싱글톤 빈에 가변 인스턴스 필드를 두지 않았는가
* 상태가 필요하면 메서드 지역 변수나 파라미터로 처리하는가
* 카운터 등 누적 상태는 원자적 연산을 쓰는가

```java
// 점검 대상: 싱글톤 빈에 가변 상태 (스레드 간 공유로 경쟁 발생)
@Service
public class CounterService {
    private int count = 0;  // 여러 스레드가 동시 접근

    public void increment() {
        count++;  // 원자적이지 않음
    }
}

// 개선: 원자적 연산 사용
@Service
public class CounterService {
    private final AtomicInteger count = new AtomicInteger();

    public void increment() {
        count.incrementAndGet();
    }
}
```

## 2. 공유 자원 접근

여러 스레드가 같은 자원을 변경할 때 안전한지 본다.

점검 항목
* 공유 컬렉션에 동시성 안전 구현체를 쓰는가(ConcurrentHashMap 등)
* 복합 연산(확인 후 수정)이 원자적으로 보호되는가
* 가변 객체를 스레드 간에 공유하지 않는가

```java
// 점검 대상: 일반 HashMap을 여러 스레드가 동시 수정
private final Map<String, Integer> cache = new HashMap<>();

// 개선: 동시성 안전 컬렉션 사용
private final Map<String, Integer> cache = new ConcurrentHashMap<>();
```

## 3. 데이터 동시 수정

같은 레코드를 여러 요청이 동시에 수정할 때 갱신 손실을 막는지 본다.

점검 항목
* 갱신 손실 가능성이 있는 흐름에 낙관적 또는 비관적 잠금을 적용했는가
* 재고 차감 등 정합성이 중요한 연산을 보호했는가

```java
// 점검 대상: 조회 후 수정 사이에 다른 트랜잭션이 끼어들면 갱신 손실
Stock stock = stockRepository.findById(id).orElseThrow();
stock.decrease(quantity);

// 개선: 낙관적 잠금 적용 (Entity에 @Version 필드 추가)
@Entity
public class Stock {
    @Version
    private Long version;
}
```

## 4. 비동기와 스케줄러

비동기 실행과 주기 작업의 동작이 안전한지 본다.

점검 항목
* @Async 메서드가 같은 클래스 내부 호출로 동작하지 않게 되어 있지 않은가(프록시 우회 주의)
* @Async 실행 중 예외가 유실되지 않도록 처리했는가
* 스케줄러가 다중 인스턴스 환경에서 중복 실행되지 않도록 막았는가
* 비동기 작업이 트랜잭션 경계와 영속성 컨텍스트를 넘나들 때 지연 로딩 문제가 없는가
