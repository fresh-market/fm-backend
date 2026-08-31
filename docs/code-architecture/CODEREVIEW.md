# 코드 리뷰 가이드라인

이 디렉터리는 Pull Request 자동 코드 리뷰의 기준을 정의한다.
이 문서(CODEREVIEW.md)는 진입점 역할을 하며, 공통 규칙과 각 영역 문서로의 링크, 그리고 변경 경로별 적용 매핑을 담는다.

대상 기술 스택은 Java, Spring, MySQL 8.4를 기준으로 한다.

## 문서 구성

각 영역은 점검 항목을 정의한 가이드 문서와, 그 항목이 왜 필요한지 설명하는 근거 문서로 짝을 이룬다.
가이드는 리뷰 시 점검 기준으로 쓰고, 근거는 판단이 애매할 때 맥락을 이해하기 위해 참고한다.

| 영역 | 점검 가이드 | 근거 문서 |
|------|-------------|-----------|
| 자바 작성 원칙 (Effective Java 기반) | [effective-java-guideline.md](./effective-java-guideline.md) | [effective-java-rationale.md](./effective-java-rationale.md) |
| 단위 테스트 (Unit Testing 기반) | [unit-testing-guideline.md](./unit-testing-guideline.md) | [unit-testing-rationale.md](./unit-testing-rationale.md) |
| API 설계 (Google AIP 기반) | [api-design-guideline.md](./api-design-guideline.md) | [api-design-rationale.md](./api-design-rationale.md) |
| JPA 사용 (RDB 관점) | [jpa-rdb-guideline.md](./jpa-rdb-guideline.md) | [jpa-rdb-rationale.md](./jpa-rdb-rationale.md) |
| 베이스 엔티티 (PK, 시각 컬럼) | [base-entity-guideline.md](./base-entity-guideline.md) | [base-entity-rationale.md](./base-entity-rationale.md) |
| 엔티티 생성 패턴 | [entity-creation-guideline.md](./entity-creation-guideline.md) | [entity-creation-rationale.md](./entity-creation-rationale.md) |
| 식별자 전략 (내부, 외부, 비즈니스) | [identifier-strategy-guideline.md](./identifier-strategy-guideline.md) | [identifier-strategy-rationale.md](./identifier-strategy-rationale.md) |
| 도메인 패키지 경계 | [domain-package-boundary-guideline.md](./domain-package-boundary-guideline.md) | [domain-package-boundary-rationale.md](./domain-package-boundary-rationale.md) |

## 봇 동작 규칙

자동 리뷰 봇은 다음 규칙을 따른다.

1. 지적의 기준은 점검 가이드 문서(`*-guideline.md`)의 점검 항목으로 한정한다.
2. 근거 문서(`*-rationale.md`)는 코멘트 설명을 보강할 때만 참고하고, 근거 문서를 바탕으로 새로운 지적을 만들지 않는다.
3. 가이드에 없는 항목은 지적하지 않으며, 새 점검 기준이 필요하면 가이드 문서를 먼저 갱신한다.
4. 가이드에 포함된 외부 링크와 출처(AIP 번호, 책 항목 번호, URL 등)는 사람 리뷰어와 작성자를 위한 참고일 뿐이다. 봇은 이 링크를 가져오지 않으며, 가이드 본문에 적힌 내용만으로 판단한다. 외부 페이지를 읽지 않으면 판단이 어려운 항목은 임의로 가져오지 말고, 확인이 필요하다는 점을 코멘트로 남겨 사람에게 넘긴다.
5. 한 사안에 대해서는 한 번만 지적한다. 여러 가이드가 같은 문제를 다룰 수 있으므로, 봇은 아래 소유권 우선순위에 따라 그 사안을 소유한 가이드 하나만 기준으로 코멘트를 남긴다.
6. 모든 리뷰 코멘트는 한국어로 작성한다. 지적 강도 접두어(`[BLOCKER]` 등), 코드 식별자, 애너테이션, 파일 경로, AIP 번호 같은 고유 표기는 원문 그대로 두되, 설명과 제안 문장은 한국어로 쓴다.

### 중복 지적 방지 소유권 우선순위

같은 코드 한 줄에 여러 가이드가 걸릴 때, **더 구체적이고 좁은 범위를 다루는 가이드가 그 사안을 소유한다.**
봇은 소유 가이드에서만 지적하고, 더 일반적인 가이드의 동일 항목은 발화하지 않는다.

엔티티 관련 가이드가 셋이라 겹침이 가장 많다. 축을 나눠 소유를 정한다.

| 사안 | 소유 가이드 | 지적 보류 |
|------|-------------|-----------|
| 엔티티 인스턴스 생성 (정적 팩터리, 검증 위치, 생성용 Lombok) | entity-creation-guideline.md | effective-java-guideline.md, jpa-rdb-guideline.md |
| 엔티티 뼈대 (베이스 상속, PK 타입, Auditing, 시각 컬럼) | base-entity-guideline.md | jpa-rdb-guideline.md |
| 외부 노출 식별자가 필요한가, UUID 버전, 난수원, 컬럼 스펙 | identifier-strategy-guideline.md | base-entity-guideline.md |
| 어느 베이스를 상속하는가 (`BasePublic*` 여부 포함) | base-entity-guideline.md | identifier-strategy-guideline.md |
| 연관관계 매핑, cascade, 자동 매핑, DTO 프로젝션 | jpa-rdb-guideline.md | - |
| 엔티티 속성값의 저장 방식 (enum 대 코드 테이블) | entity-creation-guideline.md | base-entity-guideline.md |
| 패키지 배치, 도메인 간 참조, 접근 제어자, 순환 의존 | domain-package-boundary-guideline.md | - |
| 자바 관용 (불변, 예외 흐름, 컬렉션 반환, 상속보다 조합) | effective-java-guideline.md | - |
| 테스트 설계와 품질 (동작 검증, 테스트 더블, 구조, 격리) | unit-testing-guideline.md | - |
| API 표면 설계 (리소스, 표준 메서드, 필드명, 페이지네이션, 오류 구조) | api-design-guideline.md | - |

### common 저장소가 소유하는 사안

`fresh-market/.github`의 `docs/software-quality/`가 시스템 품질 속성을 다룬다.
아래 사안은 그쪽이 소유하므로 이 디렉터리의 가이드로 지적하지 않는다.

| 사안 | 소유 문서와 항목 |
|------|-----------------|
| 트랜잭션 경계, 외부 호출 위치, 트랜잭션 길이 | `qa-data-integrity-guideline.md` 4장 (`DI-4-*`) |
| 잠금 전략, 획득 순서, 갱신 손실 | `qa-data-integrity-guideline.md` 2장 (`DI-2-*`) |
| N+1, 인덱스, 쿼리 성능 | `qa-performance-efficiency-guideline.md` 2장 (`PERF-2-*`) |
| 인가와 소유권 검증 | `qa-security-guideline.md` 1장 (`SEC-1-*`) |
| 타임아웃, 재시도, 서킷 브레이커 | `qa-reliability-guideline.md` 2장 (`REL-2-*`) |

**반대로 아래는 이 디렉터리가 소유한다.** common에서 이관받은 사안이다.

| 사안 | 소유 가이드 |
|------|-------------|
| 도메인 경계, 순환 의존, 내부 타입 교환 | domain-package-boundary-guideline.md |
| 테스트 실행 시간과 테스트 설계 품질 | unit-testing-guideline.md |
| 목록 응답의 페이지네이션 구조 | api-design-guideline.md |

경계 기준은 **common은 "얼마나 잘 하는가"(품질 속성), 이 디렉터리는 "어떻게 쓰는가"(코드 관용과 패턴)**다.

해석 원칙은 다음과 같다.

- **같은 사안이라도 관점이 다르면 중복이 아니다.** 예를 들어 엔티티 클래스 하나에서 base-entity는 상속 대상을, entity-creation은 생성 경로를 보므로 둘 다 발화할 수 있다. 표는 "같은 문제를 같은 관점으로 두 번 지적하는 것"만 막는다.
- 우선순위가 불분명하면 더 좁은 범위를 다루는 가이드를 소유로 본다.
- `id` 관련 지적은 대상이 갈린다. 내부 PK의 타입과 생성 전략은 base-entity, 외부 노출 식별자는 identifier-strategy, `id`를 생성 파라미터로 받는지는 entity-creation이 소유한다.

## 가이드 적용 대상 판단

effective-java-guideline.md와 domain-package-boundary-guideline.md는 변경 위치와 무관하게 모든 프로덕션 자바 PR에 항상 적용한다.
나머지는 변경 내용을 기준으로 적용 여부를 판단한다.

**테스트 코드(`src/test/**`, `src/integrationTest/**`)는 이 둘의 대상이 아니다.**
두 문서는 프로덕션 코드를 겨냥하므로 목 주입, 픽스처 빌더, 서술형 메서드명이 전부 지적으로 나온다.
테스트는 unit-testing-guideline.md가 소유한다.
예외는 ArchitectureTest다. 테스트 파일이지만 판정 대상이 경계 규칙 자체이므로 domain-package-boundary-guideline.md를 적용한다.

이 프로젝트는 도메인형 구조(package-by-feature)를 사용한다.
도메인 패키지 안에 Controller, Service, Repository, Entity가 함께 모이므로 계층 디렉터리로 영역을 구분할 수 없다.
따라서 적용 판단은 내용 시그널을 주된 기준으로 삼고, 파일명 힌트는 보조로만 쓴다.

1. 내용 시그널: diff에 아래 시그널이 보이면 해당 가이드를 적용한다. (주된 기준)
2. 파일명 힌트: 시그널 판단을 빠르게 좁히기 위한 보조 단서로 쓴다.
3. 애매하면 적용: 관련성이 불확실하면 적용하는 쪽을 택한다. 불필요한 코멘트가 누락된 점검보다 비용이 낮기 때문이다.

### 내용 시그널 (주된 기준)

| 가이드 | 적용 시그널 |
|--------|-------------|
| jpa-rdb-guideline.md | `@Entity`, `@ManyToOne`, `@OneToMany`, `@OneToOne`, `cascade`, `fetch`, `@Query`, `EntityManager`, `JdbcTemplate`, `@Transactional`, SQL 문자열, `.sql` 파일 |
| base-entity-guideline.md | `@MappedSuperclass`, `@Id`, `@GeneratedValue`, `@CreatedDate`, `@LastModifiedDate`, `@EnableJpaAuditing`, 엔티티 클래스 선언 |
| entity-creation-guideline.md | `@Entity` 클래스의 생성자와 정적 팩터리, `@Builder`, `@Setter`, `@Data`, `@NoArgsConstructor`, `@Enumerated` |
| identifier-strategy-guideline.md | `UUID`, `public_id`, `BINARY(16)`, `SecureRandom`, `@UuidGenerator`, 응답 DTO의 식별자 필드 |
| domain-package-boundary-guideline.md | 패키지 이동, import 문 변경, 접근 제어자 변경, `~Api` 인터페이스, `~ApiImpl`, `internal.client`, ArchUnit 테스트, `Admin` 접두사와 `/admin` 경로 |
| unit-testing-guideline.md | `@Test`, JUnit, Mockito, AssertJ, `@DataJpaTest`, `@SpringBootTest`, 테스트 클래스(`*Test`) |
| api-design-guideline.md | `@RestController`, `@RequestMapping`, `@GetMapping`/`@PostMapping`/`@PatchMapping`/`@DeleteMapping`, 요청과 응답 DTO, `.proto` 파일, OpenAPI 명세 |

### 파일명 힌트 (보조)

최종 적용 여부는 위의 내용 시그널로 확정한다.

| 파일명 패턴 | 적용 문서 |
|-------------|-----------|
| `src/main/**` 의 모든 변경 | effective-java-guideline.md, domain-package-boundary-guideline.md |
| `**/*Entity.java`, `**/entity/**` | base-entity-guideline.md, entity-creation-guideline.md, jpa-rdb-guideline.md |
| `**/*Repository.java`, `**/*.sql`, `db/migration/**` | jpa-rdb-guideline.md, identifier-strategy-guideline.md |
| `**/*Controller.java`, `**/dto/**`, `**/*.proto`, OpenAPI 명세 | api-design-guideline.md |
| `**/*Api.java`, `**/*ApiImpl.java`, `**/client/**` | domain-package-boundary-guideline.md |
| `**/*Test.java`, `src/test/**`, `src/integrationTest/**` | unit-testing-guideline.md |
| `**/ArchitectureTest.java` | domain-package-boundary-guideline.md |

### 도메인 경계 점검의 자동화

도메인형 구조에서는 경로보다 도메인 간 경계가 더 중요하다.
ArchUnit 아키텍처 테스트로 "한 도메인 패키지가 다른 도메인의 내부 클래스에 의존하지 않는다", "의존은 정해진 방향으로만 흐른다" 같은 규칙을 강제한다.

**이는 보조 수단이 아니라 필수다.** 계층을 나눈 대가로 `Service`, `Repository`, `Entity`가 public이 되므로, 접근 제어자만으로는 경계가 지켜지지 않는다.
구체적인 규칙과 그 동작 원리는 domain-package-boundary-guideline.md 6절에 있다.

## 지적 강도 분류

리뷰 코멘트는 다음 접두어 중 하나를 붙여 우선순위를 명확히 한다.

| 접두어 | 의미 | 머지 차단 여부 |
|--------|------|----------------|
| `[BLOCKER]` | 반드시 수정해야 머지 가능 | 차단 |
| `[MAJOR]` | 강하게 권장하는 수정 | 협의 후 결정 |
| `[MINOR]` | 제안 수준 | 비차단 |
| `[NIT]` | 단순 의견, 취향 | 비차단 |

## 리뷰 코멘트 작성 원칙

1. 문제를 지적할 때는 이유와 개선 방향을 함께 제시한다.
2. 단정적 명령보다 근거를 들어 설명한다.
3. 좋은 부분도 함께 언급하여 균형을 맞춘다.
4. 지적이 과도하게 많으면 BLOCKER와 MAJOR부터 정리해서 전달한다.

## 자동 리뷰 출력 형식 예시

```
[BLOCKER] Order.java:42
  public 생성자가 열려 있어 검증 생성자를 거치지 않는 생성 경로가 생깁니다.
  정적 팩터리 하나로 노출하고 검증을 private 생성자에 모아 주세요.
  (참고: entity-creation-guideline.md R2)

[BLOCKER] OrderService.java:18
  다른 도메인의 내부 패키지(product.domain.service)를 import 하고 있습니다.
  ProductApi 를 통해 호출해 주세요.
  (참고: domain-package-boundary-guideline.md 1절)

[MAJOR] AccountResponse.java:7
  응답 DTO 에 내부 Long id 가 노출되어 있습니다.
  한 번 노출되면 클라이언트가 의존해 되돌릴 수 없으므로 public_id 를 내보내 주세요.
  (참고: identifier-strategy-guideline.md 7절)

[MINOR] AccessLog.java:12
  수정되지 않는 이력 테이블인데 BaseMutableTimeEntity 를 상속하고 있습니다.
  BaseImmutableTimeEntity 가 적합해 보입니다.
  (참고: base-entity-guideline.md 1절)
```
