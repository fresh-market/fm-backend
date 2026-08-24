# 기술 스택 (backend)

작성 기준일: 2026-08-08
적용 대상: 이 저장소의 Spring Boot 애플리케이션

이 저장소가 쓰는 언어, 프레임워크, 라이브러리와 그 선정 근거를 정리한다.
`build.gradle` 을 쓰거나 고칠 때 여기를 본다.

**인프라가 강제하는 것은 여기 없다.** MySQL 8.4 와 Valkey 9.0 은 RDS 와 ElastiCache 가 지원하는 버전에서 정해진 것이라
`fresh-market/fm-infra` 의 [기술 스택 확정 문서](https://github.com/fresh-market/fm-infra/blob/main/docs/system-design/%EB%B0%B1%EC%97%94%EB%93%9C%EA%B3%B5%ED%86%B5_%EA%B8%B0%EC%88%A0%EC%8A%A4%ED%83%9D_%ED%99%95%EC%A0%95%EB%AC%B8%EC%84%9C.md) 2.3절이 갖는다.

빌드 게이트(커버리지, 정적 분석)는 점검 항목과 근거를 따로 두었다.
[build-gate-guideline.md](./code-architecture/build-gate-guideline.md), [build-gate-rationale.md](./code-architecture/build-gate-rationale.md)

---

## 1. 애플리케이션

| 구분 | 기술 | 버전 | 근거 |
|------|------|------|------|
| OS | Ubuntu | 24.04 LTS | 2029년까지 공식 지원 |
| Language | Java | 21 LTS | 안정성 높고 실무 채택 증가 |
| Framework | Spring Boot | 4.0.5 | 3.5 지원 종료(2026-06-30) 대응 |
| Framework | Spring Framework | 7.0.x | Spring Boot 4.x가 자동 적용 |
| Build Tool | Gradle | 최신 (Spring Boot 4 플러그인) | |
| Container | Docker | 29.4.1 | |
| Container Orchestration (Local) | Docker Compose | - | 로컬, CI, 운영 환경 동일화 |

## 2. 애플리케이션 라이브러리

**아래 항목은 버전을 명시하지 않는다.** Spring Boot 4.0.5 의 BOM 이 관리하며, 여기에 버전을 박으면 Boot 를 올릴 때 일부만 옛 버전에 묶여 검증된 조합이 깨진다.

| 구분 | 기술 | 버전 | 관리 주체 |
|------|------|------|-----------|
| ORM | Spring Data JPA | BOM 관리 | Spring Boot 4.0.5 |
| ORM | Hibernate | BOM 관리 (Boot 4 는 Hibernate 7 계열) | Spring Boot 4.0.5 |
| Migration | Flyway | BOM 관리 | Spring Boot 4.0.5 |
| Boilerplate | Lombok | BOM 관리 | Spring Boot 4.0.5 |
| Integration Test | Testcontainers | BOM 관리 (Boot 4.0.5 는 2.0.4) | Spring Boot 4.0.5 |
| Query | QueryDSL | BOM 관리 (`querydsl-bom` 5.1.0) | Spring Boot 4.0.5 |

Terraform AWS Provider 를 `~> 6.0` 으로 고정한 것과 반대 논리다. 프로바이더는 BOM 이 없어 직접 고정해야 하지만, 이쪽은 **BOM 이 검증한 조합을 그대로 쓰는 것이 목적**이다.

BOM 밖에 있어 직접 지정해야 하는 항목은 다음과 같다.

| 구분 | 기술 | 버전 | 비고 |
|------|------|------|------|
| API Docs | springdoc-openapi | 3.0.3 | 4장 참조. Boot 4.0.5 를 parent 로 빌드된 버전이다 |
| Coverage | JaCoCo | 0.8.15 | service 패키지 메서드 100% 미만이면 병합 차단 |
| Static Analysis | SonarQube Cloud | - | 버그, 취약점만 판정. **Blocker 이슈가 있으면 병합 차단** |
| Static Analysis | SonarScanner for Gradle | 최신 | Gradle 7.6.4 또는 8.4 이상 필요 |

**통합 테스트는 인메모리 DB 를 쓰지 않는다.** Testcontainers 로 운영과 동일한 `mysql:8.4` 를 띄운다. 근거는 2.3절의 버전 일치 원칙과 같다.

**Testcontainers 2.x 에서 산출물 이름이 바뀌었다.** 1.x 좌표를 그대로 쓰면 해석되지 않는다.

```gradle
// 1.x 좌표. Boot 4.0.5 의 BOM 에서 해석되지 않는다
'org.testcontainers:junit-jupiter'
'org.testcontainers:mysql'

// 2.x 좌표
'org.testcontainers:testcontainers-junit-jupiter'
'org.testcontainers:testcontainers-mysql'
```

## 3. QueryDSL: 채택하되 착수 전에 검증한다

**동적 쿼리 수단으로 QueryDSL 을 채택한다.** 버전은 Boot 4.0.5 의 BOM 이 관리하고, 스파이크는 APT 동작만 확인한다.

#### 왜 QueryDSL 인가

| | 의존성 | Boot 4 위험 | 타입 안전 | 동적 조건 | 학습 |
|---|---|---|---|---|---|
| **QueryDSL** | APT 설정 | 낮음 (BOM 검증) | 강함 | **가장 좋음** | 중간 |
| Spring Data JPA Specification | **없음** | **없음** | 부분 | 보통 | 중간 |
| JPQL + `@Query` | **없음** | **없음** | 없음 | **나쁨** | 낮음 |
| jOOQ | 코드 생성 + DB | 낮음 | **가장 강함** | 좋음 | 높음 |
| MyBatis | 별도 매핑 | 낮음 | 없음 | 좋음 | 중간 |

**조건이 여러 개 붙는 검색이 이 서비스의 주된 조회 형태다.** 상품 목록에 분류, 가격대, 재고 유무, 정렬이 동시에 걸린다.

* `Specification` 은 조인과 서브쿼리가 들어가면 급격히 읽기 어려워진다
* `JPQL` 은 조건 수만큼 메서드가 늘거나 `is null or` 로 지저분해진다
* `jOOQ` 는 표현력이 가장 좋지만 **스키마에서 코드를 생성하는 파이프라인**이 필요하다. Flyway 마이그레이션과 빌드를 엮어야 해서 이 단계에서는 과하다
* `MyBatis` 는 JPA 와 **두 매핑 체계를 동시에 유지**해야 한다

`BooleanExpression` 이 `null` 이면 조건이 자동으로 빠지는 점이 결정적이다. 조건 조합이 늘어도 코드가 선형으로만 늘어난다.

`jpa-rdb-guideline.md` 의 "조회는 DB 관점으로, 복합 객체는 별도 레이어에서 조합" 과도 맞는다.

#### 버전은 BOM 이 관리한다

초판은 QueryDSL 을 "BOM 밖이라 버전을 직접 지정해야 하는 항목" 으로 분류했다. **사실이 아니었다.**

```
spring-boot-dependencies-4.0.5.pom

  <querydsl.version>5.1.0</querydsl.version>
    <groupId>com.querydsl</groupId>
    <artifactId>querydsl-bom</artifactId>
    <version>${querydsl.version}</version>
```

**Spring Boot 4.0.5 가 QueryDSL 5.1.0 을 검증해 BOM 에 올려 두었다.** 버전을 적지 않는다.

**jakarta 분류자를 빼면 안 된다.** 빼면 `javax.persistence` 를 쓰는 변형이 걸려 Boot 4 에서 깨진다.

```gradle
implementation 'com.querydsl:querydsl-jpa::jakarta'
annotationProcessor 'com.querydsl:querydsl-apt::jakarta'
annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
```

OpenFeign 포크를 검토할 이유도 없어졌다. **Spring 팀이 조합을 검증한 본가 버전이 BOM 에 있다.**

#### 그래도 스파이크는 한다

과거 이력 때문이다. QueryDSL 은 5.0 이 2년 넘게 방치된 뒤 나온 메이저 릴리스였고, 이후에도 Hibernate 호환 문제가 반복됐다. Spring Boot 3.x 시절에는 QueryDSL 이 Hibernate 5.4.8 기준으로 빌드되어 있어 `FlushMode` 타입 불일치와 `javax.persistence` 잔존 import 문제가 보고됐고, Hibernate 6.2 에서는 `NoClassDefFoundError` 가 발생했다.

**BOM 이 버전을 맞춰 준다고 애노테이션 프로세서가 도는 것까지 보장하지는 않는다.**
Q클래스 생성은 빌드 도구와 Gradle 구성에 걸리는 문제라 BOM 밖의 영역이다.

판정 기준이 바뀌었다.

```
초판   무엇을 쓸지 고른다 (본가냐 포크냐 대안이냐)
지금   BOM 이 준 5.1.0 이 도는지 확인한다
```

막히면 `Specification` 으로 전환한다. 의존성이 없고 호환 위험이 0 이라 즉시 갈아탈 수 있다.
대신 조인이 들어간 조회는 읽기 어려워진다.

**착수 전에 스파이크로 검증한다.** 엔티티 하나에 Q클래스를 생성하고 조인이 포함된 쿼리를 실행하면 30분 안에 판별된다. 여기서 막히면 대안을 택해야 하는데, **코드를 다 짠 뒤에 알면 되돌리기 어렵다.**

스파이크 판정 기준은 다음과 같다.

| 확인 | 통과 조건 |
|------|-----------|
| Q클래스 생성 | 애노테이션 프로세서가 정상 동작 |
| 단순 조회 | 예외 없이 결과 반환 |
| 조인 쿼리 | 동일 |
| 페이징 | `Pageable` 연동 동작 |
| 트랜잭션 | Spring 트랜잭션 경계 안에서 동작 |

## 4. API 문서: springdoc-openapi 를 택한 이유

**Spring REST Docs 를 쓰지 않는다.** 초판은 REST Docs 였고, 아래 이유로 바꿨다.

| | springdoc-openapi | Spring REST Docs |
|---|---|---|
| 문서의 출처 | 컨트롤러와 DTO 를 스캔 | 통과한 테스트 |
| 초기 비용 | **의존성 한 줄** | 테스트마다 필드 선언 |
| 엔드포인트 누락 | 없다. 자동 스캔 | 테스트를 안 쓰면 누락 |
| Swagger UI | **기본 제공** | `restdocs-api-spec` 을 얹어야 한다 |
| 실제 동작 보장 | **없다** | 있다 |
| 문서가 낡는가 | **낡는다** | 어긋나면 테스트가 실패한다 |

**REST Docs 의 값어치는 "문서가 거짓말하지 않는다" 하나이며, 그 대가로 엔드포인트마다 `requestFields` 와 `responseFields` 를 손으로 선언해야 한다.**
엔드포인트가 늘수록 비용이 커지고, Swagger UI 까지 가려면 서드파티(`restdocs-api-spec`)를 하나 더 얹어야 한다.

컨트롤러 기반 자동 등록으로 얻는 것이 이 단계에서 더 크다고 판단했다.

```
/v3/api-docs        OpenAPI 3 JSON
/swagger-ui.html    Swagger UI
```

어노테이션 없이도 경로, HTTP 메서드, 요청과 응답 스키마, 검증 제약(`@NotNull`, `@Size`)이 자동으로 채워진다.
`@Operation` 과 `@Schema` 는 설명을 보강할 때만 쓴다.

**포기한 것을 분명히 해 둔다.**

* 문서와 구현이 어긋나도 빌드가 깨지지 않는다. 응답에 필드를 추가하고 설명을 안 고쳐도 아무 일이 없다
* 실제 요청과 응답 예시가 없다. 스키마 기본값 수준이다
* **API 명세가 테스트를 거치지 않는다.** 테스트가 하나도 없어도 Swagger 는 완성된다

마지막 항목 때문에 **커버리지 게이트는 API 명세와 무관한 별개 규율이다.**
"통합 테스트가 없으면 명세가 비어서 병합이 막힌다" 는 구조는 성립하지 않는다. 병합을 막는 것은 [build-gate-guideline.md](./code-architecture/build-gate-guideline.md) 의 service 패키지 커버리지다.

#### BOM 밖이라 버전을 직접 고정한다

**애플리케이션 라이브러리 중 유일하게 BOM 밖에 있다.** QueryDSL 은 BOM 이 관리하고(3장), Testcontainers 와 Flyway 도 마찬가지다.

```
spring-boot-dependencies-4.0.5.pom 에 springdoc 항목이 없다
```

그래서 **Boot 를 올릴 때 springdoc 호환 버전을 함께 확인해야 한다.** 7장 재확인 목록에 넣어 둔다.

확인 방법은 springdoc 의 `pom.xml` 이 어떤 `spring-boot-starter-parent` 를 상속하는지 보는 것이다. 패치까지 대응된다.

```
springdoc 3.0.3  ->  Boot 4.0.5   <- 채택
springdoc 3.1.0  ->  Boot 4.1.0
springdoc 2.9.0  ->  Boot 3.5.16  (구 라인)
```

자동 추적(Dependabot)은 두지 않는다. **BOM 밖 라이브러리가 이것 하나뿐이라 주간 PR 설비를 둘 값어치가 없고**, Boot 업그레이드를 어차피 손으로 하므로 그때 함께 보면 된다.
서드파티가 서넛으로 늘면 그때 다시 검토한다.

취약점 알림은 별개다. 저장소 설정의 Dependabot alerts 로 켜며 설정 파일이 필요 없다.

**착수 전에 엔드포인트 하나로 `/swagger-ui.html` 이 뜨는지 확인한다.**
막히면 대안은 OpenAPI 명세를 손으로 쓰는 것뿐이므로, **코드를 다 짠 뒤에 알면 되돌리기 어렵다.**

---
