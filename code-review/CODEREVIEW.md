# 코드 리뷰 가이드라인

이 디렉터리는 Pull Request 자동 코드 리뷰의 기준을 정의한다.
이 문서(CODEREVIEW.md)는 진입점 역할을 하며, 공통 규칙과 각 영역 문서로의 링크, 그리고 변경 경로별 적용 매핑을 담는다.

대상 기술 스택은 Java, Spring, MySQL 8.4를 기준으로 한다.

## 문서 구성

각 영역은 점검 항목을 정의한 가이드 문서와, 그 항목이 왜 필요한지 설명하는 근거 문서로 짝을 이룬다.
가이드는 리뷰 시 점검 기준으로 쓰고, 근거는 판단이 애매할 때 맥락을 이해하기 위해 참고한다.

| 영역 | 점검 가이드 | 근거 문서 |
|------|-------------|-----------|
| 기능과 코드 품질 | [functional.md](./functional.md) | [functional-rationale.md](./functional-rationale.md) |
| 자바 작성 원칙 (Effective Java 기반) | [effective-java-guideline.md](./effective-java-guideline.md) | [effective-java-rationale.md](./effective-java-rationale.md) |
| 객체 지향 설계 원칙 (SOLID) | [solid-principles.md](./solid-principles.md) | [solid-principles-rationale.md](./solid-principles-rationale.md) |
| 단위 테스트 (Unit Testing 기반) | [unit-testing-guideline.md](./unit-testing-guideline.md) | [unit-testing-rationale.md](./unit-testing-rationale.md) |
| API 설계 (Google AIP 기반) | [api-design-guideline.md](./api-design-guideline.md) | [api-design-rationale.md](./api-design-rationale.md) |
| 데이터베이스 | [database.md](./database.md) | [database-rationale.md](./database-rationale.md) |
| JPA 사용 (RDB 관점) | [jpa-rdb-guideline.md](./jpa-rdb-guideline.md) | [jpa-rdb-rationale.md](./jpa-rdb-rationale.md) |
| 보안 | [security.md](./security.md) | [security-rationale.md](./security-rationale.md) |
| 동시성 | [concurrency.md](./concurrency.md) | [concurrency-rationale.md](./concurrency-rationale.md) |

## 봇 동작 규칙

자동 리뷰 봇은 다음 규칙을 따른다.

1. 지적의 기준은 점검 가이드 문서(functional.md 등)의 점검 항목으로 한정한다.
2. 근거 문서(rationale)는 코멘트 설명을 보강할 때만 참고하고, 근거 문서를 바탕으로 새로운 지적을 만들지 않는다.
3. 가이드에 없는 항목은 지적하지 않으며, 새 점검 기준이 필요하면 가이드 문서를 먼저 갱신한다.
4. 가이드에 포함된 외부 링크와 출처(AIP 번호, 책 항목 번호, URL 등)는 사람 리뷰어와 작성자를 위한 참고일 뿐이다. 봇은 이 링크를 가져오지 않으며, 가이드 본문에 적힌 내용만으로 판단한다. 외부 페이지를 읽지 않으면 판단이 어려운 항목은 임의로 가져오지 말고, 확인이 필요하다는 점을 코멘트로 남겨 사람에게 넘긴다.
5. 한 사안에 대해서는 한 번만 지적한다. 여러 가이드가 같은 문제를 다룰 수 있으므로, 봇은 아래 소유권 우선순위에 따라 그 사안을 소유한 가이드 하나만 기준으로 코멘트를 남기고, 다른 가이드의 동일 항목으로 중복 지적하지 않는다.
6. 모든 리뷰 코멘트는 한국어로 작성한다. 지적 강도 접두어(`[BLOCKER]` 등), 코드 식별자, 애너테이션, 파일 경로, AIP 번호 같은 고유 표기는 원문 그대로 두되, 설명과 제안 문장은 한국어로 쓴다.

### 중복 지적 방지 소유권 우선순위

같은 코드 한 줄에 여러 가이드가 걸릴 때, 더 구체적이고 전문적인 가이드가 그 사안을 소유한다.
봇은 소유 가이드에서만 지적하고, 더 일반적인 가이드(특히 functional.md)의 동일 항목은 발화하지 않는다.

| 사안 | 소유 가이드 | 일반 가이드(지적 보류) |
|------|-------------|------------------------|
| 단일 책임(SRP), 의존성 역전(DIP, 추상화 의존) | solid-principles.md | functional.md 설계 항목 |
| 자바 관용(빈 컬렉션 반환, 불변, 상속보다 조합, try-with-resources 자원 해제, 예외 흐름제어·도메인 예외 변환·예외 무시) | effective-java-guideline.md | functional.md 예외 항목 |
| 테스트 설계와 품질(동작 검증, 테스트 더블, 구조, 격리) | unit-testing-guideline.md | functional.md 테스트 항목 |
| N+1, 인덱스, 트랜잭션 롤백 | database.md | functional.md, jpa-rdb-guideline.md |
| 엔티티 설계(테이블 대응, cascade, 자동 매핑, DTO 프로젝션) | jpa-rdb-guideline.md | functional.md |
| 입력 검증, 인젝션, 인가, 민감 정보 노출 | security.md | api-design-guideline.md, functional.md |
| API 표면 설계(리소스, 표준 메서드, 필드명, 페이지네이션, 오류 구조) | api-design-guideline.md | functional.md |

해석 원칙은 다음과 같다.
- functional.md는 다른 전문 가이드가 소유하지 않은 일반 사항만 지적하는 진입점이다. 위 표의 사안이면 전문 가이드에 양보한다.
- 같은 사안이라도 관점이 다르면 중복이 아니다. 예를 들어 Controller 변경에서 security.md는 입력 검증과 인가를, api-design-guideline.md는 표면 설계를 보므로 둘 다 발화할 수 있다. 표는 "같은 문제를 같은 관점으로 두 번 지적하는 것"만 막는다.
- 우선순위가 불분명하면, 더 좁은 범위를 다루는 가이드를 소유로 본다.

## 가이드 적용 대상 판단

functional.md, effective-java-guideline.md, solid-principles.md는 변경 위치와 무관하게 모든 자바 PR에 항상 적용한다.
도메인 가이드(database, security, concurrency)는 변경 내용을 기준으로 적용 여부를 판단한다.

이 프로젝트는 도메인형 구조(package-by-feature)를 사용한다.
도메인 패키지(`order`, `user` 등) 안에 Controller, Service, Repository, Entity가 함께 모이므로, 계층 디렉터리로 영역을 구분할 수 없다.
따라서 적용 판단은 내용 시그널을 주된 기준으로 삼고, 파일명 힌트는 보조로만 쓴다.

1. 내용 시그널: diff에 아래 시그널이 보이면 해당 가이드를 적용한다. (주된 기준)
2. 파일명 힌트: 시그널 판단을 빠르게 좁히기 위한 보조 단서로 쓴다.
3. 애매하면 적용: 관련성이 불확실하면 적용하는 쪽을 택한다. 불필요한 코멘트(false positive)가 누락된 점검(false negative)보다 비용이 낮기 때문이다.

### 내용 시그널 (주된 기준)

도메인형 구조에서는 코드 위치로 영역을 가를 수 없으므로, 변경 내용의 시그널이 최종 기준이다.

| 가이드 | 적용 시그널 |
|--------|-------------|
| database.md | `@Entity`, `@Repository`, `@Query`, `EntityManager`, `JdbcTemplate`, `@Transactional`, SQL 문자열, `.sql` 파일 |
| jpa-rdb-guideline.md | `@Entity`, `@ManyToMany`, `@OneToMany`, `@OneToOne`, `cascade`, `@JdbcTypeCode`, 연관관계 매핑, 엔티티 변경 |
| security.md | `@RequestBody`, `@RequestParam` 등 외부 입력 처리, 비밀번호와 토큰 등 민감 정보, 인증과 인가 로직, 문자열로 조합한 SQL |
| concurrency.md | `@Async`, `@Scheduled`, 싱글톤 빈(`@Service`, `@Component`)의 가변 인스턴스 필드, `static` 가변 필드, `synchronized`, 공유 컬렉션 |
| unit-testing-guideline.md | `@Test`, JUnit, Mockito, AssertJ, `@DataJpaTest`, `@SpringBootTest`, 테스트 클래스(`*Test`) |
| api-design-guideline.md | `@RestController`, `@RequestMapping`, `@GetMapping`/`@PostMapping`/`@PatchMapping`/`@DeleteMapping`, 요청과 응답 DTO, `.proto` 파일, OpenAPI 명세 |

### 파일명 힌트 (보조)

도메인 패키지가 섞여 있어도 파일명 접미사로 1차 후보를 좁힐 수 있다. 단, 최종 적용 여부는 위의 내용 시그널로 확정한다.

| 파일명 패턴 | 적용 문서 |
|-------------|-----------|
| 모든 변경 | functional.md, effective-java-guideline.md, solid-principles.md |
| `**/*Repository.java`, `**/*Entity.java`, `**/*.sql`, `db/migration/**` | database.md, jpa-rdb-guideline.md |
| `**/*Controller.java`, 인증과 인가 관련 클래스, 보안 설정 클래스 | security.md |
| `**/*Service.java`, 비동기 및 스케줄러 클래스 | concurrency.md |
| `**/*Test.java`, `src/test/**` | unit-testing-guideline.md |
| `**/*Controller.java`, `**/*.proto`, `**/dto/**`, OpenAPI 명세 파일 | api-design-guideline.md |

### 도메인 경계 점검 유지 (보조 수단)

도메인형 구조에서는 경로보다 도메인 간 경계가 더 중요하다.
ArchUnit 같은 아키텍처 테스트로 "한 도메인 패키지가 다른 도메인의 내부 클래스에 의존하지 않는다", "의존은 정해진 방향으로만 흐른다" 같은 규칙을 강제하면, 구조가 흐트러지는 것을 코드 리뷰 전에 자동으로 막을 수 있다.
이는 필수가 아니며, 내용 시그널 기반 판단과 도메인 결합 점검(functional.md)을 보강하는 안전장치다.
이는 필수가 아니며, 내용 시그널 기반 판단을 보강하는 안전장치다.

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
[BLOCKER] OrderService.java:42
  @Transactional 메서드 내부에서 IOException(checked)을 던지지만
  rollbackFor 설정이 없어 롤백되지 않습니다.
  rollbackFor를 지정하거나 unchecked 예외로 감싸 주세요.
  (참고: database.md 트랜잭션 항목)

[MAJOR] OrderRepository.java:18
  findAll 호출 후 루프에서 member를 조회하여 N+1이 발생합니다.
  fetch join 또는 @EntityGraph 적용을 권장합니다.
  (참고: database.md N+1 항목)

[MINOR] UserController.java:30
  매직 넘버 30을 상수 MAX_INACTIVE_DAYS로 분리하면 가독성이 좋아집니다.
  (참고: functional.md 가독성 항목)
```
