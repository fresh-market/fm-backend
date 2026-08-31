# 도메인 패키지 경계 리뷰 가이드

이 문서는 모놀리식 구조에서 도메인 패키지 간 경계 규칙을 코드 리뷰 점검 항목으로 정리한 가이드다.
패키지 배치, 클래스 접근 제어자, 도메인 간 호출이 포함되거나 변경되는 PR에 적용한다.

기준 스택은 Java, Spring, MySQL 8.4이며, 별도 라이브러리 없이 ArchUnit만으로 경계를 강제한다.
각 항목이 왜 필요한지는 [domain-package-boundary-rationale.md](./domain-package-boundary-rationale.md)를 참고한다.

핵심 원칙은 하나다. **도메인 루트에 API와 DTO만 두고, 나머지는 전부 `domain` 아래로 내린다.**

> **코드 예시의 도메인 이름은 모두 예시다.**
> `order`, `member`, `product`, `payment` 같은 이름과 베이스 패키지 `com.freshmarket`는 규칙을 설명하기 위한 것이며, 실제 프로젝트에서는 각자의 이름으로 바꿔 적용한다.

## 1. 패키지 구조

베이스 패키지의 **직계 하위 패키지가 곧 도메인**이다. 도메인 안의 구현은 전부 `domain` 하위로 내린다.

```
com.freshmarket
├── common                                <- 모든 도메인이 의존 가능. 도메인을 모른다
│   ├── entity                            <- BaseMutableTimeEntity, BaseImmutableTimeEntity
│   ├── response                          <- ResponseEnvelope, PageResponse
│   └── exception                         <- ErrorCode, CommonErrorCode, BusinessException, GlobalExceptionHandler
├── config                                <- 모든 도메인이 의존 가능한 공통 설정
│   └── SecurityConfig.java
│
├── product                               <- 다른 도메인이 호출하는 도메인
│   ├── ProductApi.java                   <- 다른 도메인에 공개하는 API 인터페이스
│   ├── ProductInfo.java                  <- API에서 사용하는 DTO (record)
│   ├── StockChange.java                  <- API에서 사용하는 DTO (record)
│   ├── OutOfStockException.java          <- 외부에도 노출 가능한 예외
│   │
│   └── domain                            <- 도메인 내부 구현 (외부 도메인 접근 금지)
│       ├── ProductApiImpl.java           <- ProductApi 구현체 (package-private)
│       ├── controller
│       │   └── ProductController.java
│       ├── service
│       │   └── ProductService.java       <- 핵심 비즈니스 로직
│       ├── repository
│       │   └── ProductRepository.java
│       ├── entity
│       │   ├── Product.java              <- @Entity. 공통 베이스 엔티티 상속
│       │   └── ProductStatus.java        <- 엔티티에 묶이는 enum
│       ├── dto                           <- 내부 전용 DTO
│       │   └── ProductSearchCondition.java
│       └── exception                     <- 내부 전용 예외
│           ├── ProductException.java
│           └── ProductErrorCode.java
│
├── payment                               <- 외부 시스템을 연동하는 도메인
│   ├── PaymentApi.java
│   ├── PaymentResult.java
│   └── domain
│       ├── PaymentApiImpl.java
│       ├── controller
│       │   └── PgWebhookController.java  <- 가상계좌 입금 등 비동기 통보 수신
│       ├── service
│       │   └── PaymentService.java
│       ├── repository
│       ├── entity
│       ├── exception
│       │   ├── PaymentException.java
│       │   └── PaymentErrorCode.java
│       └── client                        <- 외부 시스템 호출 (PG, 외부 API)
│           ├── PgClient.java             <- 인터페이스. payment가 소유한다
│           ├── TossPgClient.java         <- 구현체
│           └── dto
│               ├── PgPayRequest.java     <- 외부 스펙 전용 DTO
│               └── PgPayResponse.java
│
└── order                                 <- 아무도 호출하지 않는 도메인
    │                                        (다른 도메인에 공개하는 API 인터페이스 없음)
    └── domain
        ├── controller
        │   └── OrderController.java
        ├── service
        │   └── OrderService.java         <- product를 호출하는 쪽
        ├── repository
        │   └── OrderRepository.java
        ├── entity
        │   ├── Order.java                <- @Entity
        │   └── OrderStatus.java          <- 주문 상태. 엔티티와 함께 둔다
        ├── dto
        │   └── OrderPlaceRequest.java
        └── exception
            ├── OrderException.java
            └── OrderErrorCode.java
```

점검 항목
* `DPB-1-01` 도메인 루트에 API 인터페이스, record DTO, 공개 예외 외의 것이 없는가
  `@Entity`, Repository, Controller, 구현 클래스가 루트에 올라오면 다른 도메인이 영속 모델에 직접 의존하게 된다.
* `DPB-1-02` 다른 도메인의 `domain` 패키지를 import하지 않는가
* `DPB-1-03` API를 호출당하지 않는 도메인에도 의무적으로 만들지 않았는가
  쓰지 않는 API를 미리 만들면 유지할 계약만 늘고 아무도 소비하지 않는다.
* `DPB-1-04` `~ApiImpl`이 계층 안이 아니라 `domain` 바로 아래에 있는가
  "이 도메인이 밖에 무엇을 제공하는지"를 한 곳에서 찾을 수 있어야 한다.

## 2. DTO, 예외, enum의 위치

판단 기준은 하나다. **다른 도메인이 이 타입을 참조해야 하는가.**

| 종류 | 위치 | 예시 |
|------|------|------|
| 공개 DTO | 도메인 루트 | `ProductInfo`, `StockChange` |
| 내부 DTO | `internal.dto` | `OrderPlaceRequest`, 조회 조건 |
| 공개 예외 | 도메인 루트 | `OutOfStockException` (다른 도메인이 catch 해야 함) |
| 내부 예외 | `internal.exception` | `ProductException` + `ProductErrorCode` |

| enum 성격 | 위치 | 예시 |
|------|------|------|
| 엔티티에 묶이는 상태 | `internal.entity` | `OrderStatus`, `ProductStatus` |
| 공개 DTO가 노출하는 값 | 도메인 루트 | `OrderSummary`가 상태를 담는다면 |
| 여러 도메인이 공유 | `common` | `Currency` |

점검 항목
* `DPB-2-01` 공개 DTO에 엔티티를 그대로 담지 않았는가
  `record ProductInfo(Product product)`는 감춘 것이 없다. 필드를 값으로 풀어 담는다.
* `DPB-2-02` 공개 DTO가 내부 enum을 그대로 노출하지 않는가
  노출하면 그 enum이 계약의 일부가 되어 상수 추가 시 다른 도메인 영향을 검토해야 한다.
* `DPB-2-03` 도메인 예외를 통합 예외 하나와 에러 코드 enum으로 두었는가
  실패 종류마다 예외 클래스를 만들면 클래스 수가 계속 늘어난다.
* `DPB-2-04` 다른 도메인이 구분해 처리해야 하는 실패만 별도 예외로 루트에 공개했는가
* `DPB-2-05` 에러 코드를 `common`의 enum 하나에 모으지 않았는가
  그 파일이 전 도메인 지식을 갖게 되어 경계가 무너진다. 도메인마다 접두사로 구분한다.

## 3. 외부 시스템 호출

PG, 외부 REST API, 메시지 발송처럼 외부 시스템을 호출하는 코드는 **그것을 사용하는 도메인 안**에 둔다.

```
payment
└── domain
    └── client
        ├── PgClient.java          <- 인터페이스. payment 가 소유한다
        ├── TossPgClient.java      <- 구현체
        └── dto
            ├── PgPayRequest.java  <- 외부 스펙 전용 DTO
            └── PgPayResponse.java
```

점검 항목
* `DPB-3-01` 인터페이스를 도메인이 소유하고 구현체가 외부 스펙에 맞추는가
  `PaymentService`는 `PgClient`만 알고 어느 PG사인지 모른다.
* `DPB-3-02` 외부 스펙 DTO를 도메인 밖으로 내보내지 않는가
  `client.dto`에 가두고 도메인이 쓰는 형태로 변환해 넘긴다.
* `DPB-3-03` 최상위에 `client` 패키지를 두지 않았는가
  모든 도메인이 접근 가능해져 경계가 무너진다.
* `DPB-3-04` `client` 클래스에 `@Transactional`이 없는가
  네트워크 대기 중 DB 락이 유지된다.

**웹훅과 콜백은 `client`가 아니다.** `client`는 나가는 호출만 담는다.
외부가 우리를 호출하는 경우는 HTTP 엔드포인트이므로 `controller`에 둔다.

점검 항목
* `DPB-3-05` 웹훅 수신 시 본문을 검증하는가
* `DPB-3-06` 웹훅 처리가 멱등한가
  전송 실패 시 재시도가 오고, 구독 이벤트가 여럿이면 같은 상태 변경에 여러 번 도착할 수 있다.

| 상황 | 배치 |
|------|------|
| 한 도메인만 쓴다 (PG, 배송사 API) | 그 도메인의 `internal.client` |
| 여러 도메인이 쓴다 (SMS, 이메일, 파일 저장소) | 별도 도메인으로 승격 후 공개 API 제공 |
| 도메인 지식이 없는 기술 설정 (HTTP 타임아웃, 재시도 정책) | `config` |

## 4. 이름 규칙

### 4.1 공개 창구

공개 창구는 도메인명 + `Api`(`ProductApi`, `MemberApi`)로 통일한다.
구현체는 `~ApiImpl`로 이름 짓고 `domain` 바로 아래에 package-private로 둔다.

점검 항목
* `DPB-4-01` 공개 창구 이름이 도메인명 + `Api` 형태인가
* `DPB-4-02` 구현체 이름에 패턴명(`Facade` 등)을 넣지 않았는가
* `DPB-4-03` 자기 도메인 컨트롤러가 API를 경유하지 않는가
  같은 `domain` 안이므로 내부 서비스를 직접 쓴다. API는 도메인 간 협력 전용이다.
* `DPB-4-04` 통과 위임만 하는 API 구현체가 아닌가
  변환이나 노출 범위 축소가 없으면 노이즈다.
* `DPB-4-05` API 구현체에 비즈니스 로직이 없는가
  조합과 변환만 하고 규칙 판단은 내부 서비스에 남긴다.

### 4.2 계층 접미사

계층 패키지에 두는 클래스는 그 계층 이름을 접미사로 갖는다.

| 패키지 | 접미사 |
|---|---|
| `internal/controller` | `~Controller` |
| `internal/service` | `~Service` |
| `internal/repository` | `~Repository` |

**커버리지 게이트가 `service` 패키지 전체를 100%로 요구한다**(`BLD-1-01`). 그 패키지에 정책 객체나
계산 헬퍼를 함께 두면 그것들에도 100%가 요구된다. 이름을 강제하면 "여기 있는 것은 전부 서비스다"가
성립해서, 대상 선정과 실제 내용이 어긋나지 않는다.

이 규칙은 `ArchitectureTest`가 빌드에서 강제한다. 어기면 `./gradlew check`가 실패한다.

**계층 패키지에 접미사를 붙일 수 없는 클래스는 그 계층에 속하지 않는다.** 정책 객체나 계산 헬퍼는
`service` 패키지가 아니라 `domain` 바로 아래나 도메인 루트에 둔다.

점검 항목
* `DPB-4-10` 계층 패키지의 클래스가 그 계층 이름을 접미사로 갖는가
  `service` 패키지는 전체가 커버리지 100% 대상이므로, 서비스가 아닌 클래스를 두면 그것까지 100%를 요구받는다.

### 4.3 회원용과 관리자용

한 도메인에 소비자가 둘이면 **관리자 쪽에만 `Admin`을 앞에 붙인다.** 회원용이 기본이라 접두사가 없다.

| 소비자 | URI | 컨트롤러 | 서비스 |
|---|---|---|---|
| 회원 | `/v1/products` | `ProductController` | `ProductService` |
| 관리자 | `/v1/admin/products` | `AdminProductController` | `AdminProductService` |

**URI와 클래스 이름이 같은 순서로 간다.** `/v1/admin/products`를 보면 `AdminProduct~`를 찾으면 된다.
경로 조각이 이름 조각에 그대로 대응하므로 둘 사이에 번역 규칙이 필요 없다.

붙이는 위치가 앞인 이유는 **정렬**이다. 뒤에 붙이면 파일명 순으로 볼 때 두 소비자가 번갈아 나온다.

```
Admin 접두사 (채택)            Admin 접미사
  AdminProductService            ProductAdminService
  AdminProductStatService        ProductService
  ProductService                 ProductStatAdminService
  ProductStatService             ProductStatService
```

왼쪽은 관리자용이 위에 모여 **한 덩어리로 보인다.** 권한 검토나 감사 대상을 훑을 때 이 덩어리가 그대로 목록이 된다.

**엔티티와 레포지토리에는 붙이지 않는다.** 저장 모델은 소비자가 누구든 하나다.
관리자 전용 조회가 필요하면 같은 레포지토리에 메서드를 더한다.

점검 항목
* `DPB-4-06` 관리자 전용 컨트롤러, 서비스, DTO의 이름이 `Admin`으로 시작하는가
  중간이나 끝에 넣으면 정렬로 구분하려던 목적이 사라진다.
* `DPB-4-07` 회원용 클래스에 `Member`, `User`, `Client` 같은 접두사를 붙이지 않았는가
  양쪽에 다 붙이면 접두사가 아무것도 구분해 주지 못한다. 회원용이 기본값이다.
* `DPB-4-08` 클래스 이름의 `Admin` 유무가 URI의 `/admin` 유무와 일치하는가
  한쪽만 바꾸면 경로에서 클래스를 찾는 경로가 끊긴다.
* `DPB-4-09` 엔티티와 레포지토리에 `Admin`을 붙이지 않았는가
  저장 모델이 소비자별로 갈라지면 같은 테이블에 두 개의 매핑이 생긴다.

## 5. 의존 방향

계층을 정해 두고 위에서 아래로만 흐르게 한다. 계층 구분은 예시이며 프로젝트가 정한다.

```
L2 조합    order, claim
              |
L1 정책    payment, coupon, point, cart
              |
L0 기반    member, product
              |
      common, config (도메인 무지)
```

점검 항목
* `DPB-5-01` 같은 층끼리 직접 호출하지 않는가
  필요하면 상위 조합 도메인이 조립한다.
* `DPB-5-02` 순환 의존이 없는가
* `DPB-5-03` `common`이 특정 도메인을 참조하지 않는가
  `@ExceptionHandler(OutOfStockException.class)`처럼 개별 도메인 예외를 직접 잡으면 경계가 무너진다.

## 6. 강제 장치

세 겹으로 건다.

### 6.1 접근 제어자 (1차, 언어 차원)

점검 항목
* `DPB-6-01` `~ApiImpl`과 `Controller`가 package-private인가
  Spring은 package-private 빈도 정상적으로 등록하고 주입한다.
* `DPB-6-02` 계층 간 참조가 없는데 public으로 선언하지 않았는가

계층을 나눈 대가로 `Service`, `Repository`, `Entity`는 public이 된다.
따라서 1차 방어만으로는 부족하고 6.2의 ArchUnit 규칙이 실질적인 경계 강제를 담당한다.

### 6.2 ArchUnit 아키텍처 테스트 (2차, 빌드 차원)

```java
@AnalyzeClasses(packages = "com.freshmarket",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // 다른 도메인의 domain 패키지 접근 금지
    @ArchTest
    static final ArchRule domainIsHidden = slices()
            .matching("com.freshmarket.(*)..")
            .namingSlices("$1")
            .should().notDependOnEachOther()
            .ignoreDependency(
                    resideInAPackage("com.freshmarket.."),
                    resideInAnyPackage(
                            "com.freshmarket.*",          // 도메인 루트만 허용
                            "com.freshmarket.common..",
                            "com.freshmarket.config.."));

    // 도메인 내부 계층 방향 강제
    @ArchTest
    static final ArchRule layerDirection = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Controller").definedBy("..internal.controller..")
            .layer("Service").definedBy("..internal.service..")
            .layer("Repository").definedBy("..internal.repository..")
            .layer("Client").definedBy("..internal.client..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Client").mayOnlyBeAccessedByLayers("Service");

    // API 구현체에 트랜잭션 금지
    @ArchTest
    static final ArchRule apiImplHasNoTransaction = noClasses()
            .that().haveSimpleNameEndingWith("ApiImpl")
            .should().beAnnotatedWith(Transactional.class);

    @ArchTest
    static final ArchRule apiImplMethodsHaveNoTransaction = noMethods()
            .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("ApiImpl")
            .should().beAnnotatedWith(Transactional.class);

    // 외부 연동 클래스에 트랜잭션 금지
    @ArchTest
    static final ArchRule clientHasNoTransaction = noClasses()
            .that().resideInAPackage("..internal.client..")
            .should().beAnnotatedWith(Transactional.class);

    // 순환 의존 금지
    @ArchTest
    static final ArchRule noCycles = slices()
            .matching("com.freshmarket.(*)..")
            .should().beFreeOfCycles();

    // 도메인 루트에 엔티티 유출 금지
    @ArchTest
    static final ArchRule noEntityInRoot = noClasses()
            .that().resideInAPackage("com.freshmarket.*")
            .should().beAnnotatedWith(Entity.class);

    // 도메인 루트는 인터페이스, record, 예외만
    @ArchTest
    static final ArchRule rootIsContractOnly = classes()
            .that().resideInAPackage("com.freshmarket.*")
            .should().beInterfaces()
            .orShould().beRecords()
            .orShould().beAssignableTo(RuntimeException.class);
}
```

점검 항목
* `DPB-6-03` ArchUnit 테스트가 빌드 파이프라인에 묶여 있는가
  계층 구조에서는 테스트가 유일한 자동 검증 수단이다.
* `DPB-6-04` `consideringOnlyDependenciesInLayers()`가 적용되어 있는가
  없으면 계층으로 선언하지 않은 패키지와의 의존까지 검사되어 오탐이 발생한다.
* `DPB-6-05` `entity`, `dto`, `exception`을 계층으로 선언하지 않았는가
  여러 계층이 자유롭게 참조하는 타입이라 방향 제약 대상이 아니다.

### 6.3 코드 리뷰 규칙 (3차, 사람 차원)

* import에 다른 도메인의 `.domain`이 보이면 `[BLOCKER]`

## 7. 트랜잭션 경계

동기 호출은 트랜잭션이 도메인을 가로질러 길어지는 문제를 구조적으로 안고 있다.

> **트랜잭션 경계와 잠금 전략의 점검 항목은 common 저장소가 소유한다.**
> `fresh-market/.github`의 `docs/software-quality/qa-data-integrity-guideline.md` 4장(`DI-4-*`)과 2장(`DI-2-*`)을 따른다.
> 그쪽이 MySQL 격리 수준과 잠금 전략 선택표를 갖고 있어 판정 근거가 더 강하다.

이 절은 **도메인 경계 때문에 트랜잭션이 길어지는 구조**만 다룬다. 판정은 위 문서가 하고, 여기서는 왜 그런 구조가 생기는지를 설명한다.

점검 항목
* `DPB-7-01` API 구현체와 `client` 클래스에 `@Transactional`이 없는가
  경계 선언을 내부 서비스 한 곳으로 모으기 위한 규칙이며, 6.2절 ArchUnit이 강제한다.

```java
// 점검 대상: 조회, 재고 차감, 외부 결제가 모두 한 트랜잭션에 들어 있다
@Transactional
public long place(OrderPlaceRequest request) {
    List<ProductInfo> products = productApi.findAllByIds(request.productIds());
    productApi.decreaseStockAll(toStockChanges(request.items()));  // 재고 락
    PaymentResult result = paymentApi.pay(request.paymentInfo());  // 이 동안 락 유지
    return orderRepository.save(Order.place(...)).getId();
}

// 개선: 락 구간을 DB 쓰기로 좁힌다
public long place(OrderPlaceRequest request) {
    List<ProductInfo> products = productApi.findAllByIds(request.productIds());
    PaymentResult result = paymentApi.pay(request.paymentInfo());
    return orderTransactionService.saveOrder(request, products, result);
}
```

## 8. 동기 호출과 이벤트

| 상황 | 방식 |
|------|------|
| 다른 도메인의 로직을 호출해 **결과를 받아야** 할 때 (동작 의존) | API (동기 호출) |
| 어떤 일이 벌어졌을 때 다른 도메인이 **반응만** 하면 될 때 (부수 효과) | 이벤트 |

**도메인 간 협력은 동기 호출을 기본으로 한다.** 이벤트는 아래 중 하나에 해당하는 협력 지점만 국소적으로 바꾼다.

* 7절의 대응으로도 트랜잭션이 충분히 짧아지지 않을 때
* 순환이 우회로로 풀리지 않을 때
* 반응해야 할 도메인이 계속 늘어날 때 (알림, 통계, 이력)
* 특정 도메인을 별도 서비스로 분리할 계획이 확정됐을 때

## 9. 함정 정리

| 함정 | 원칙 |
|------|------|
| 도메인 루트에 엔티티나 리포지토리를 둠 | 루트에는 API, DTO, 예외만 |
| API 구현체를 도메인 루트에 둠 | 구현은 `domain` 안에 package-private로 |
| 불필요하게 public으로 선언 | `~ApiImpl`과 `Controller`는 package-private 유지 |
| API 구현체에 `@Transactional` | 트랜잭션 경계 선언은 내부 서비스 한 곳으로 모은다 |
| API 구현체에 비즈니스 로직 | 조합과 변환만. 규칙 판단은 내부 서비스에 |
| 공개 DTO에 엔티티 담기 | 필드를 값으로 풀어 담는다 |
| 자기 도메인 컨트롤러가 API 경유 | 같은 `domain` 안이므로 내부 서비스를 직접 쓴다 |
| 통과 위임만 하는 API 구현체 | 변환이나 노출 범위 축소가 없으면 노이즈다 |
| 최상위에 `client` 패키지를 둠 | 사용하는 도메인의 `internal.client`에 둔다 |
| 외부 스펙 DTO를 도메인 밖으로 노출 | `client.dto`에 가두고 변환해 넘긴다 |
| `client` 클래스에 `@Transactional` | 트랜잭션 밖에서 호출한다 |
| 공개 DTO에 내부 enum을 그대로 노출 | 필요하면 `String`으로 변환한다 |

## 10. 도입 순서

한 번에 뒤집지 않고 순서를 둔다.

1. **순환 의존부터 없앤다.** `noCycles` 규칙을 먼저 통과시킨다.
2. **패키지를 도메인 단위로 재배치한다.** 구현을 `domain` 아래로 내린다.
3. **`~ApiImpl`과 `Controller`를 package-private로 바꾼다.**
4. **기반 도메인부터 API를 만든다.** 가장 많이 참조되는 하위 도메인이 효과가 크다.
5. **상위 도메인으로 확장하고, 마지막에 `domainIsHidden` 규칙을 켠다.**
6. **남은 위반은 무시 처리하지 말고 별도 목록으로 관리한다.** 잔량이 줄어드는 게 보이게 한다.

## 11. 참고

- Migrating to Modular Monolith using Spring Modulith and IntelliJ IDEA: https://blog.jetbrains.com/idea/2026/02/migrating-to-modular-monolith-using-spring-modulith-and-intellij-idea/
- kgrzybek/modular-monolith-with-ddd: https://github.com/kgrzybek/modular-monolith-with-ddd
- 토스페이먼츠 웹훅 이벤트: https://docs.tosspayments.com/reference/using-api/webhook-events
