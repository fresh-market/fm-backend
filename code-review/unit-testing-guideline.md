# 단위 테스트 리뷰 가이드

이 문서는 Vladimir Khorikov의 Unit Testing(Manning, 2020)이 제시하는 단위 테스트 원칙을 코드 리뷰 점검 항목으로 정리한 가이드다.
테스트 코드가 포함되거나 변경되는 PR에 적용한다.

각 항목은 책의 원칙을 바탕으로 하되, 설명과 예시는 이 가이드에서 새로 작성했다.
상세한 근거와 배경은 원서를 참고한다. 이 문서는 책의 본문이나 코드를 옮긴 것이 아니라 원칙을 점검 기준으로 재구성한 것이다.
기본적인 테스트 유무 점검은 functional.md의 테스트 항목과 겹치며, 이 문서는 테스트의 설계와 품질을 더 깊게 다룬다.

기준 스택은 Java, JUnit, Spring, MySQL 8.4이다.

## 1. 좋은 테스트의 네 기둥

좋은 단위 테스트는 네 가지 속성을 균형 있게 갖춰야 한다.

점검 항목
* 회귀 방어: 테스트가 실제 버그를 잡아내는가
  의미 있는 로직을 실행하고 결과를 검증해야 회귀를 잡는다. 단순 getter만 호출하는 테스트는 방어력이 약하다.
* 리팩터링 내성: 동작이 그대로인데 내부 구현만 바꿔도 테스트가 깨지지 않는가
  구현 세부에 묶인 테스트는 리팩터링마다 깨져 거짓 경보를 낸다.
* 빠른 피드백: 테스트가 빠르게 실행되는가
  느린 테스트는 자주 돌리지 않게 되어 가치가 떨어진다.
* 유지보수성: 테스트가 읽기 쉽고 고치기 쉬운가
  테스트가 복잡하면 코드보다 테스트를 이해하는 데 시간이 더 든다.

이 중 리팩터링 내성이 가장 자주 간과되며, 거짓 경보가 쌓이면 팀이 테스트를 신뢰하지 않게 된다.

## 2. 무엇을 검증할 것인가

테스트는 내부 구현이 아니라 관찰 가능한 동작을 검증해야 한다.

점검 항목
* 결과나 상태 변화 등 외부에서 관찰 가능한 동작을 검증하는가
* 내부 메서드 호출 순서나 횟수 같은 구현 세부를 검증하지 않는가
* private 메서드를 직접 테스트하지 않고 공개 API를 통해 검증하는가
  private 메서드를 따로 테스트하고 싶어진다면, 그것이 별도 책임이라는 신호일 수 있다.

```java
// 점검 대상: 내부 호출을 검증해 구현에 묶임 (리팩터링에 취약)
verify(orderRepository, times(1)).save(any());

// 개선: 관찰 가능한 결과를 검증
Order saved = orderService.place(request);
assertThat(saved.getStatus()).isEqualTo(OrderStatus.PLACED);
```

## 3. 테스트 구조

테스트는 일관된 구조와 명확한 이름을 가져야 한다.

점검 항목
* 준비(given), 실행(when), 검증(then) 단계가 구분되는가
* 하나의 테스트가 하나의 동작 단위를 검증하는가
  한 테스트에 여러 실행(when)이 섞이면 무엇을 검증하는지 흐려진다.
* 테스트 이름이 어떤 상황에서 무엇을 기대하는지 드러내는가
* 조건 분기나 반복 같은 로직을 테스트 안에 넣지 않는가
  테스트에 로직이 들어가면 테스트 자체에 버그가 생길 수 있다.

```java
@Test
void 재고가_부족하면_주문에_실패한다() {
    // given
    Stock stock = new Stock(0);

    // when, then
    assertThatThrownBy(() -> stock.decrease(1))
        .isInstanceOf(OutOfStockException.class);
}
```

## 4. 테스트 더블

테스트 더블(mock, stub)은 목적에 맞게 구분해 써야 한다.

점검 항목
* 들어오는 데이터를 제공하는 의존성은 stub으로 두고 검증 대상으로 삼지 않는가
  stub과의 상호작용을 verify로 검증하면 구현에 묶인다.
* 나가는 호출(부수 효과)을 검증할 때만 mock으로 검증하는가
* 모든 의존성을 무분별하게 모킹하지 않는가
  과도한 모킹은 테스트를 구현 세부에 묶고 리팩터링 내성을 떨어뜨린다.

```java
// 점검 대상: 입력을 제공하는 stub과의 상호작용을 검증 (구현에 묶임)
when(memberReader.find(1L)).thenReturn(member);
verify(memberReader).find(1L);  // 불필요한 검증

// 개선: stub은 입력만 제공하고, 결과로 동작을 검증
when(memberReader.find(1L)).thenReturn(member);
Order order = orderService.place(request);
assertThat(order.getMemberId()).isEqualTo(1L);
```

## 5. 통합 테스트와 의존성

외부 의존성은 종류에 따라 다르게 다뤄야 한다.

점검 항목
* 데이터베이스처럼 우리가 관리하고 외부에 노출되지 않는 의존성은 실제로 사용해 통합 테스트하는가
  DB를 mock으로 대체하면 실제 쿼리와 매핑의 오류를 잡지 못한다.
* 외부 결제 API처럼 우리가 통제할 수 없는 공유 의존성만 mock으로 대체하는가
* 통합 테스트가 관리 의존성과의 실제 연동(쿼리, 트랜잭션, 매핑)을 검증하는가

```java
// 점검 대상: DB(관리 의존성)를 mock으로 대체해 실제 연동을 검증하지 못함
when(orderRepository.save(any())).thenReturn(order);

// 개선: 통합 테스트에서는 실제 DB를 사용해 매핑과 쿼리까지 검증
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

## 6. 테스트 코드 품질

테스트 코드도 운영 코드와 같은 수준으로 관리해야 한다.

점검 항목
* 테스트 코드의 가독성과 중복을 운영 코드만큼 신경 쓰는가
* 테스트 간에 상태를 공유해 서로 영향을 주지 않는가
  공유 상태는 실행 순서에 따라 결과가 달라지는 깨지기 쉬운 테스트를 만든다.
* 커버리지 숫자 자체를 목표로 삼지 않는가
  높은 커버리지가 곧 좋은 테스트는 아니다. 검증 없이 실행만 하는 테스트도 커버리지는 올린다.
