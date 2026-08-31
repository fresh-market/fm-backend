# 도메인 패키지 경계 점검 항목의 근거

이 문서는 [domain-package-boundary-guideline.md](./domain-package-boundary-guideline.md)의 점검 항목이 왜 필요한지를 구체적인 예시와 함께 설명한다.
이 배치는 모듈러 모놀리스에서 널리 쓰이는 관례를 따른 것이며, Spring Modulith 라이브러리를 도입하지 않고 같은 구조를 ArchUnit으로 강제한다.

## 1. 왜 도메인 루트에 경계를 긋는가

### 레이어 하위에 그으면 나머지가 무방비로 남는다

경계선을 `service` 같은 레이어 하위에 그으면 같은 레벨의 `repository`, `entity`가 그대로 열린다.
**막아야 할 대상은 service만이 아니라 repository와 entity 전부다.**

도메인 루트에 경계를 두면 "루트만 공개, 나머지는 전부 비공개"라는 한 줄 규칙으로 도메인 전체가 덮인다.

### 레이어별 패키징이 낳는 세 가지 문제

| 문제 | 내용 |
|------|------|
| 구조가 하는 일을 못 드러낸다 | 프로젝트를 열면 기술 폴더만 보이고 도메인이 안 보인다 |
| 모든 타입이 public이 된다 | 여러 곳에서 쓰려다 보니 public을 붙이게 되고 기능별 공개 API라는 개념이 사라진다 |
| 변경이 위험해진다 | 기능 하나를 바꾸려면 여러 기술 패키지를 동시에 손대야 하고 영향 범위가 안 보인다 |

### 왜 domain 안은 다시 계층으로 나누는가

앞 절이 배격한 것은 **레이어를 경계로 삼는 것**이고, 여기서 하는 것은 **경계 안쪽의 정리**다.
`domain` 이하는 다른 도메인이 볼 수 없으므로 위 세 가지 문제가 발생하지 않는다.

`entity`를 따로 두는 이유는 계층 방향 규칙에서 엔티티만 예외로 다뤄야 하기 때문이다.
엔티티는 모든 계층이 참조하므로 방향 제약을 걸지 않는다.

### 왜 공개 타입을 api 하위 패키지로 묶지 않는가

API가 없는 도메인에서 **빈 `api` 패키지를 만들지 말지가 애매해진다.**
루트를 공개 영역으로 쓰면 공개할 것이 없을 때 아무것도 두지 않으면 된다.

공개 DTO가 늘어 루트가 지저분해지면, 그때 `api` 하위 패키지로 묶고 ArchUnit 허용 패턴을 `com.freshmarket.*.api..`로 바꾸면 된다.

### 왜 API를 호출당하는 도메인만 만드는가

**API는 도메인마다 의무적으로 만드는 것이 아니라, 다른 도메인이 실제로 호출할 때 만든다.**
상위 조합 도메인(`order`, `claim`)은 대개 호출당하지 않으므로 루트가 비어 있는 것이 정상이다.

쓰지 않는 API를 미리 만들면 유지할 계약만 늘고 아무도 소비하지 않는다.
나중에 호출을 받게 되면 그때 인터페이스를 루트에 추가하면 되고, 소비하는 쪽은 인터페이스만 알면 되므로 전환 비용이 거의 없다.

## 2. DTO, 예외, enum의 위치

### 왜 공개 여부로 위치가 갈리는가

공개 DTO와 예외는 **계약의 일부**이므로 함부로 바꾸면 다른 도메인이 깨진다.
내부 DTO와 예외는 자유롭게 리팩터링할 수 있다.

위치가 다르면 그 차이가 패키지 구조로 드러나, 바꾸기 전에 영향 범위를 검토해야 한다는 신호가 된다.

내부 예외가 두세 개뿐이면 패키지를 따로 만들지 않고 `domain` 바로 아래에 둬도 된다.

### 왜 엔티티 상태 enum을 entity 패키지에 두는가

엔티티의 필드이고 상태 전이 규칙도 엔티티 안에 있으므로 함께 두는 것이 응집도가 높다.

주의할 점이 하나 있다. **공개 DTO가 enum을 그대로 노출하면 그 enum이 계약의 일부가 된다.**
상수를 추가하거나 이름을 바꿀 때 다른 도메인 영향을 검토해야 하므로, 계약을 가볍게 유지하려면 DTO에서 `String`으로 변환해 내보내는 방법도 있다.

### 왜 도메인 예외를 통합하는가

실패 종류마다 예외 클래스를 만들면 클래스 수가 계속 늘어난다.
통합 예외 하나와 에러 코드 enum 하나를 두고 코드로 구분하면 에러 코드가 한곳에 모여 관리하기 쉽다.

**다만 다른 도메인이 특정 실패를 구분해 처리해야 한다면 별도 예외 타입으로 루트에 공개한다.**
에러 코드로 분기하게 하면 컴파일러 도움을 못 받고, 내부 에러 코드는 `internal.exception`에 있어 다른 도메인이 참조할 수도 없다.

### 왜 전역 예외 핸들러가 경계를 깨지 않는가

`GlobalExceptionHandler`를 `common`에 두면 모든 도메인의 예외를 알아야 할 것처럼 보인다.
그러나 `BusinessException`과 `ErrorCode`라는 **추상 타입에만 의존하면** 개별 도메인 예외를 알 필요가 없다.

핸들러는 `BusinessException` 하나만 잡아 그 안의 `ErrorCode`에서 상태 코드와 메시지를 꺼내 응답으로 변환한다.
도메인이 늘어도 핸들러를 고칠 일이 없고, 의존 방향은 여전히 도메인에서 `common`으로만 흐른다.

경계가 무너지는 것은 `@ExceptionHandler(OutOfStockException.class)`처럼 개별 도메인 예외를 직접 잡을 때다.
그 순간 `common`이 도메인을 참조하게 되어 `noCycles` 규칙에 걸린다.

## 3. 외부 시스템 호출

### 왜 client가 도메인 안에 있는가

`com.freshmarket.client`처럼 최상위에 두면 모든 도메인이 접근 가능해져서 **"결제 연동은 payment에만"이라는 경계가 무너진다.**
도메인 안에 두면 `domainIsHidden` 규칙이 자동으로 막아주므로 새 규칙도 필요 없다.

이름을 `external`이 아니라 `client`로 정한 것은 **나가는 호출**이라는 방향을 드러내기 위해서다.
`RestClient`, `@FeignClient`처럼 Spring 관례와도 맞는다.

### 왜 인터페이스를 도메인이 소유하는가

`PaymentService`는 `PgClient` 인터페이스만 알고 어느 PG사인지 모른다. PG사를 교체하면 구현체만 갈아끼운다.

헥사고날 아키텍처의 아웃바운드 포트와 같은 개념으로, 애플리케이션이 외부에 요구하는 기능을 인터페이스로 정의하고 그 구현체는 알지 못하게 한다.
의존 방향이 항상 안쪽을 향해야 한다는 규칙의 적용이기도 하다.

### 왜 외부 스펙 DTO를 가두는가

`PgPayResponse`는 PG사 응답 형식이다. 도메인 밖으로 내보내면 **PG사 스펙 변경이 도메인 전체로 번진다.**

이것이 DDD의 **부패 방지 계층(Anti-Corruption Layer)**이다.
외부 컨텍스트의 변경이 내부에 직접 영향을 주는 것을 막고 두 모델 사이를 번역한다.

실무에서는 방어 효과가 더 크다.
외부 서비스가 중복 데이터나 누락된 필드를 보내는 경우가 흔한데, 어댑터가 그 오염을 흡수해 도메인까지 번지지 않게 한다.

### 왜 웹훅이 client가 아닌가

`client`는 나가는 호출만 담는다. 외부가 우리를 호출하는 경우는 결국 HTTP 엔드포인트이므로 `controller`에 둔다.

웹훅의 비중은 결제수단에 따라 다르다.
카드나 계좌이체는 승인 API 동기 호출로 결제가 끝나므로 웹훅은 보조 수단이지만, **가상계좌는 승인 API가 계좌 발급까지만 처리하고** 구매자가 입금해야 결제가 완료되므로 입금 사실을 알 방법이 웹훅뿐이다.

### 왜 공유 외부 연동을 도메인으로 승격시키는가

SMS 발송처럼 여러 도메인이 공유하는 연동은 **소유자를 정할 수 없어** 특정 도메인의 `client`에 둘 수 없다.

별도 도메인으로 승격시키면 기존 규칙이 그대로 적용된다.
다른 도메인은 `NotificationApi`만 보고, `SmsClient`는 `notification.domain.client`에 있어 자동으로 차단된다.
**새 ArchUnit 예외를 만들 필요가 없다.**

## 4. 이름 규칙

### 왜 인터페이스와 구현을 나누는가

다른 도메인이 구현체가 아니라 계약에만 의존하게 하고(DIP), 테스트에서 목으로 대체하기 쉽게 하기 위해서다.

### 왜 클래스 이름에 패턴명을 넣지 않는가

이 구현체의 역할은 모듈 퍼사드에 해당하지만, 협력자가 내부 서비스 하나뿐이면 GoF 퍼사드 정의와 맞지 않는다.
도메인이 커져 여러 서비스를 조립하면 그때는 퍼사드가 된다.

**같은 클래스가 협력자 구성에 따라 퍼사드였다가 아니었다가 하므로**, 패턴이 아니라 "무엇의 구현인가"만 드러내는 이름을 쓴다.

### 왜 자기 도메인 컨트롤러가 API를 거치지 않는가

API는 **도메인 간 협력 전용**이다.
자기 컨트롤러까지 이를 거치게 하면 위임만 하는 계층이 하나 늘어난다.

### 왜 관리자용에만 접두사를 붙이는가

**한쪽에만 붙어야 접두사가 정보를 준다.**

`MemberProductService`와 `AdminProductService`처럼 양쪽에 붙이면 이름이 길어지기만 하고 구분은 그대로다.
정렬해도 `Admin~`과 `Member~`가 각자 다른 자리에 모여, 한 리소스의 두 표면이 오히려 더 멀어진다.

회원용을 기본값으로 두는 것은 **양이 다르기 때문**이다.
회원용 표면이 대부분이고 관리자용이 소수이므로, 소수 쪽에 표시를 다는 것이 전체 타이핑과 시선 이동을 줄인다.

### 왜 뒤가 아니라 앞에 붙이는가

**파일 목록이 소비자별로 갈리게 하기 위해서다.**

```
Admin 접두사                    Admin 접미사
  AdminOrderService               OrderAdminService
  AdminProductService             OrderService
  AdminProductStatService         ProductAdminService
  OrderService                    ProductService
  ProductService                  ProductStatAdminService
  ProductStatService              ProductStatService
```

왼쪽에서 관리자용은 **연속된 한 블록**이다. 오른쪽에서는 리소스마다 흩어져 있어 전체를 보려면 목록을 끝까지 훑어야 한다.

이 차이는 미관이 아니라 **검토 단위**의 문제다.
관리자 기능은 권한이 넓어 인가 검토와 감사 로그 점검을 따로 받는데, 그 대상 목록이 파일 정렬에서 그대로 나오면 빠뜨릴 것이 줄어든다.

디렉터리를 나누는 방법(`service/admin/`)도 있으나 쓰지 않는다.
같은 계층 안에서 패키지가 갈리면 `DPB-1-02`가 막는 경계와 이 경계가 서로 다른 층위에 생겨,
"어느 패키지까지가 한 도메인인가"라는 질문에 답이 둘이 된다.

### 왜 엔티티와 레포지토리는 예외인가

**소비자가 갈리는 것은 표면이지 저장 모델이 아니다.**

`AdminProduct` 엔티티를 만들면 같은 테이블에 매핑이 둘 생기고, 한쪽에서 바꾼 상태가 다른 쪽 영속성 컨텍스트에 반영되지 않는다.
관리자가 더 많은 필드를 보아야 한다면 그것은 엔티티가 아니라 **DTO에서 갈릴 일**이다.

레포지토리도 같다. 관리자 전용 조회는 같은 레포지토리의 메서드로 늘리고,
쿼리가 감당하기 어려울 만큼 갈라지면 그때 조회 전용 클래스를 따로 두되 이름은 용도를 쓴다(`ProductSearchRepository`).

## 5. 강제 장치

### 왜 접근 제어자만으로는 부족한가

package-private가 중요한 이유는 **컴파일 자체를 막기 때문**이다.
ArchUnit은 테스트 실행 시점에만 잡지만, package-private는 다른 패키지에서 타입 참조를 아예 불가능하게 만든다.

다만 계층을 나눈 대가로 일부 클래스가 public이 되어야 한다.

| 클래스 | 접근 제어자 | 이유 |
|--------|------|------|
| `ProductApiImpl` | package-private | `domain` 바로 아래라 패키지 밖에서 참조되지 않는다 |
| `ProductController` | package-private | 아무도 참조하지 않는다. Spring이 스캔만 한다 |
| `ProductService` | public | `controller`와 `ApiImpl`이 참조한다 |
| `ProductRepository` | public | `service`가 참조한다 |
| `Product` | public | 여러 계층이 참조한다 |

`ProductService`가 public이어도 다른 도메인에서 import하면 `domainIsHidden`이 잡는다.
잃는 것은 IDE 즉시 피드백이고 얻는 것은 도메인 내부 구조화다.
**따라서 ArchUnit 테스트를 빌드 파이프라인에 반드시 묶어야 한다.**

### domainIsHidden이 동작하는 원리

핵심은 `ignoreDependency` 두 번째 인자의 패턴에 있다.

| 패턴 | `com.freshmarket.product` | `com.freshmarket.product.internal` |
|------|------|------|
| `com.freshmarket.*` | 매칭 O | 매칭 X |
| `com.freshmarket.*..` | 매칭 O | 매칭 O (이러면 안 됨) |

`*`는 패키지 세그먼트 하나만 매칭하므로 **도메인 루트만 골라낸다.**
따라서 `order`가 `product.ProductApi`를 참조하면 허용되고, `product.domain.ProductService`를 참조하면 위반으로 잡힌다.

`common`과 `config`는 `..`를 붙여 하위까지 전부 열어둔다.

### 왜 consideringOnlyDependenciesInLayers가 필요한가

이 옵션이 없으면 계층으로 선언하지 않은 패키지(`domain` 바로 아래의 `ApiImpl`, `entity`, `dto`, 도메인 루트, `common`)와의 의존까지 검사 대상이 되어 **오탐이 발생한다.**
붙이면 선언된 네 계층 사이의 의존만 본다.

`entity`, `dto`, `exception`을 계층으로 선언하지 않은 이유도 같다.
여러 계층이 자유롭게 참조하는 타입이라 계층으로 선언하면 오히려 정상적인 참조가 위반으로 잡힌다.

### 트랜잭션 규칙의 정확한 근거

**"API 구현체가 트랜잭션을 열면 경계가 길어진다"는 설명은 부정확하다.**
Spring의 기본 전파 속성은 `REQUIRED`이므로, 호출하는 쪽에 이미 트랜잭션이 있으면 애너테이션이 있든 없든 그 트랜잭션에 참여한다.
API 구현체에서 애너테이션을 뗀다고 경계가 짧아지지 않는다. **경계 길이를 결정하는 것은 호출자다.**

이 규칙의 진짜 근거는 **트랜잭션 경계 선언을 한 곳으로 모으는 것**이다.
내부 서비스에도 `@Transactional`이 있는데 API 구현체에도 붙으면 경계가 두 군데서 선언되어 어디가 실제 경계인지 추적하기 어려워진다.
롤백 규칙이나 `readOnly` 설정이 어긋났을 때 원인을 찾기 힘들어지는 것이 실질적 위험이다.

애너테이션이 없다고 트랜잭션이 수행되지 않는 것은 아니다. 경계가 한 단계 안쪽으로 옮겨갈 뿐이다.

```
OrderService.place()                       <- 트랜잭션 없음
  └─ ProductApiImpl.findAllByIds()         <- @Transactional 없음 (규칙)
       └─ ProductService.findAllByIds()    <- @Transactional(readOnly = true) 있음
            └─ ProductRepository.findAllById()
```

**두 규칙으로 막지 못하는 것이 있다.** `@Transactional`이 없어도 호출하는 쪽의 트랜잭션에 참여하는 것은 막을 수 없다.
호출자의 `@Transactional` 자체를 금지하는 규칙은 만들지 않는다. `OrderService`는 자기 도메인의 DB 쓰기를 하므로 트랜잭션이 반드시 필요하기 때문이다.
문제는 트랜잭션을 갖는 것이 아니라 **그 안에 외부 호출과 조회까지 넣는 것**이며, 이는 규칙이 아니라 6절의 설계 지침으로 대응한다.

### 왜 보조 규칙이 필요한가

`domainIsHidden` 하나만으로는 우회로가 남는다.
누군가 `Product` 엔티티나 `ProductApiImpl`을 도메인 루트로 옮기면 그대로 노출되기 때문이다.

`noEntityInRoot`와 `rootIsContractOnly`가 그 경로를 막는다.
`rootIsContractOnly`는 특히 유효해서 구현체를 루트로 올리려는 시도를 바로 잡아준다.
`@SpringBootApplication` 클래스가 이 규칙에 걸린다면 `.and().areNotAnnotatedWith(SpringBootApplication.class)` 조건을 추가한다.

## 6. 동기 호출의 문제와 대응

### 왜 트랜잭션이 도메인을 가로질러 길어지는가

`order`가 한 트랜잭션 안에서 `payment`, `point`, `product`를 연달아 호출하면 그 전부가 하나로 묶인다.
**가장 느린 호출이 끝날 때까지 락이 유지되고**, 재고처럼 경합이 심한 자원에서는 대기가 쌓여 처리량이 떨어진다.

```java
// 나쁜 예: 조회, 재고 차감, 외부 결제 호출이 모두 한 트랜잭션에 들어 있다
@Transactional
public long place(OrderPlaceRequest request) {
    // (1) 조회. 굳이 트랜잭션 안에 있을 이유가 없다
    List<ProductInfo> products = productApi.findAllByIds(request.productIds());

    // (2) 재고 차감. 여기서 상품 행에 락이 걸린다
    productApi.decreaseStockAll(toStockChanges(request.items()));

    // (3) 외부 결제 게이트웨이 호출. 수백 ms에서 수 초가 걸릴 수 있다
    //     이 시간 내내 (2)에서 잡은 재고 락이 유지된다
    PaymentResult result = paymentApi.pay(request.paymentInfo());

    Order order = Order.place(request.memberId(), products, result.transactionId());
    return orderRepository.save(order).getId();
}
```

같은 상품을 주문하는 다른 요청은 (3)이 끝날 때까지 (2)에서 대기한다.
결제 게이트웨이가 느려지면 재고 락 대기가 쌓이고 커넥션 풀이 마르면서 장애로 번진다.

### 대응과 그 대가

트랜잭션 밖으로 뺄 수 있는 것을 빼면 락 구간이 DB 쓰기로 좁혀진다.

```java
@Service
public class OrderTransactionService {

    // 재고 차감과 주문 저장만. 외부 호출이 없으므로 트랜잭션이 짧게 끝난다
    @Transactional
    public long saveOrder(OrderPlaceRequest request,
                          List<ProductInfo> products,
                          PaymentResult result) {
        productApi.decreaseStockAll(toStockChanges(request.items()));
        Order order = Order.place(request.memberId(), products, result.transactionId());
        return orderRepository.save(order).getId();
    }
}
```

**대가가 있다.** 결제를 먼저 하고 재고를 나중에 차감하므로, 재고가 부족하면 결제를 취소하는 보상 처리가 필요해진다.
트랜잭션을 짧게 만드는 대가로 도메인 간 보상 로직이 생기는 것이 동기 호출 구조의 실제 트레이드오프다.
어느 쪽을 택할지는 결제 취소 비용과 락 대기 비용을 비교해 도메인마다 정한다.

같은 클래스 내부 호출은 프록시를 타지 않아 `@Transactional`이 무시되므로, 위 예시처럼 **다른 빈으로 분리해야 한다.**

### 왜 순환 의존이 특히 위험한가

동기 호출만 쓰는 구조에서는 이 규칙의 중요도가 특히 높다.
이벤트를 쓰면 발행자가 수신자를 몰라도 되지만, **동기 호출에서는 양방향 협력이 필요해지는 순간 곧바로 순환이 걸리기 때문이다.**

대응은 셋이다.

- 계층 규칙을 지킨다. 같은 계층끼리는 직접 호출하지 않는다.
- 두 도메인이 서로를 필요로 하면 대개 그 협력을 상위 조합 도메인으로 끌어올리면 풀린다. `coupon`과 `point`가 서로 호출하려 하면 `order`가 양쪽을 각각 호출하게 바꾼다.
- 하위 도메인이 상위 도메인의 반응을 필요로 하면, 하위는 상태만 노출하고 상위가 확인하러 오게 만든다. 재고 소진 시 대기 주문 취소가 필요하면 `product`가 `order`를 부르는 대신 `order`가 `productApi.isOutOfStock(...)`을 확인해 스스로 정리한다.

## 7. 왜 이벤트를 기본으로 쓰지 않는가

재고 차감은 **동작 의존**이다. `order`는 차감 성공 여부를 즉시 알아야 하고, 실패하면 주문을 만들 수 없다.
따라서 이벤트가 아니라 API가 맞는 경우다.

이벤트를 미루는 이유는 셋이다.

**첫째, 트랜잭션 경계가 쪼개진다.**
동기 호출은 주문 생성과 재고 차감을 한 트랜잭션에 묶어, 하나라도 실패하면 전부 롤백하는 단순한 일관성을 준다.
이벤트로 바꾸면 발행 시점과 처리 시점이 다른 트랜잭션이 되어 이 원자성이 깨진다.

**둘째, 발행 실패와 소비 실패를 직접 처리해야 한다.**
동기 호출은 실패가 곧바로 예외로 전파되어 호출한 쪽이 그 자리에서 처리한다.
이벤트는 리스너 실패, 중복 수신, 순서 뒤바뀜을 각각 대비해야 하고, 재시도와 멱등성 보장 코드가 협력마다 따라붙는다.

**셋째, 최종 일관성을 감당해야 한다.**
주문은 생겼는데 재고는 아직 안 줄어든 순간을 화면과 후속 로직이 견디도록 설계해야 한다. 도메인 규칙과 UI까지 번지는 부담이다.

전면 도입이 아니라 문제가 되는 한 곳에만 처방하는 것이므로, **지금 이벤트를 쓰지 않는 결정은 되돌릴 수 없는 선택이 아니다.**

## 8. 대안: Spring Modulith

이 지침의 구조는 Spring Modulith의 기본 배치와 동일하다.
따라서 나중에 도입하면 패키지를 그대로 두고 검증 수단만 교체할 수 있다.

| 항목 | 이 지침 (ArchUnit) | Spring Modulith |
|------|------|------|
| 경계 검증 | 슬라이스 규칙 직접 작성 | `ApplicationModules.of(...).verify()` 한 줄 |
| 허용 의존 선언 | 계층 규칙을 문서로 관리 | `@ApplicationModule(allowedDependencies = {...})` |
| 공용 모듈 개방 | ArchUnit 예외 목록에 추가 | `@ApplicationModule(type = OPEN)` |
| 도메인 단위 테스트 | 직접 구성 | `@ApplicationModuleTest` |
| IDE 지원 | 없음 | IntelliJ IDEA에서 실시간 위반 표시와 퀵픽스 |
| 문서 생성 | 없음 | C4 다이어그램 자동 생성 |
| 라이브러리 의존 | ArchUnit만 | `spring-modulith-starter-core/test` 추가 |

도메인 수가 늘고 허용 의존 관계가 복잡해지면 ArchUnit 규칙을 손으로 관리하는 비용이 커지므로, 그 시점에 전환을 검토한다.

## 9. 참고 자료

- 부패 방지 계층과 어댑터의 관계: https://medium.com/@juannegrin/building-a-restful-api-with-spring-boot-integrating-ddd-and-hexagonal-architecture-df50fe24a1ff
- 어댑터가 외부 데이터 품질 문제를 흡수한 사례: https://beyondxscratch.com/2020/08/23/hexagonal-architecture-example-digging-a-spring-boot-implementation/
- 아웃바운드 포트와 의존 방향 규칙: https://vitelco.com/vitelco-blog/hexagonal-architecture-with-spring-boot
- Spring Modulith Module Facade 패턴: https://senoritadeveloper.medium.com/modular-monolith-with-spring-boot-spring-modulith-6687c234daab
- Spring Modulith GitHub 논의: https://github.com/spring-projects/spring-modulith/discussions/478
