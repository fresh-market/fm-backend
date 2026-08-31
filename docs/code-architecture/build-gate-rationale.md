# 빌드 게이트: 설계 근거 (rationale)

점검 항목은 [build-gate-guideline.md](./build-gate-guideline.md) 에 있다.

**병합을 막는 유일한 코드 게이트다.** LLM 판정은 차단하지 않는다.
결정론적이라 오탐이 없고, 그래서 차단해도 근거가 있다.

원래 `fresh-market/fm-infra` 의 기술 스택 확정 문서 3.10절과 3.11절이었다.
판정 대상이 이 저장소의 `build.gradle` 이므로 여기로 옮겼다.

---

## 1. 커버리지 게이트 상세

#### 3.10.1 대상과 기준

| 항목 | 값 |
|------|-----|
| 대상 | `*.internal.service.*` (service 패키지 전체) |
| 판정 단위 | 클래스별 (`element = 'CLASS'`) |
| 카운터 | 메서드 (`counter = 'METHOD'`) |
| 기준 | **100%** |
| 판정 주체 | Gradle `jacocoTestCoverageVerification` |

규칙은 한 문장으로 표현된다. **service 패키지의 모든 메서드는 최소 한 번은 단위 테스트가 지나가야 한다.**

`includes` 로 대상을 좁히므로 exclude 목록이 필요 없다. config, dto, entity, Q클래스가 자동으로 빠지고, 새 패키지가 생겨도 게이트가 흔들리지 않는다.

```gradle
jacocoTestCoverageVerification {
    executionData.setFrom fileTree(layout.buildDirectory.dir('jacoco')).include('test.exec')
    violationRules {
        rule {
            element = 'CLASS'
            includes = ['*.internal.service.*']
            limit {
                counter = 'METHOD'
                value = 'COVEREDRATIO'
                minimum = 1.00
            }
        }
    }
}

check.dependsOn integrationTest, jacocoTestCoverageVerification
```

#### 3.10.2 METHOD 카운터가 세는 것

**메서드가 호출되었는지만 본다.** 메서드 안 100줄 중 1줄만 지나가도 커버된 것으로 계산된다.

```java
public void placeOrder(OrderCommand cmd) {
    validate(cmd);              // 여기서 예외 발생
    stockService.deduct(cmd);   // 실행 안 됨
    orderRepository.save(...);  // 실행 안 됨
}
```

**실패 경로만 테스트해도 이 메서드는 100% 로 계산된다.**

| 카운터 | 조건부 UPDATE 실패 경로를 검증하지 않았을 때 |
|--------|---------------------------------------------|
| METHOD | 통과 |
| LINE | 미달 가능 |
| BRANCH | 확실히 미달 |

METHOD 100% 는 **범위를 강제하는 기준이지 깊이를 강제하는 기준이 아니다.** "모든 메서드에 테스트가 있다"는 보장하지만 "모든 경로를 검증했다"는 보장하지 않는다.

깊이 검증은 게이트가 아니라 코드 리뷰와 테스트 작성 규칙으로 다룬다. 특히 **조건부 UPDATE 의 `affected rows == 0` 분기는 정합성 최종 방어선(INF-06)이므로 반드시 실패 경로 테스트를 함께 작성한다.**

나중에 깊이까지 강제하려면 BRANCH 조건을 추가한다.

```gradle
limit { counter = 'METHOD'; value = 'COVEREDRATIO'; minimum = 1.00 }
limit { counter = 'BRANCH'; value = 'COVEREDRATIO'; minimum = 0.60 }   // 필요 시
```

#### 3.10.3 100% 를 유지하기 위한 전제

기준이 100% 라 여유가 없다. **새 메서드를 하나 추가하고 테스트를 빠뜨리면 그 순간부터 모든 병합이 막힌다.** 이는 의도된 엄격함이지만 몇 가지 준비가 필요하다.

| 항목 | 내용 |
|------|------|
| 생성자 | 메서드로 계산된다. 다만 테스트가 그 클래스를 만들면 반드시 호출된다 |
| private 메서드 | 계산된다. 도달하는 경로가 있어야 한다 |
| 로컬 확인 | `./gradlew check` 로 push 전에 확인 |

초판은 `lombok.config` 에 `lombok.addLombokGeneratedAnnotation = true` 를 두라고 했다. **지금은 두지 않는다.**

`@RequiredArgsConstructor` 가 만든 생성자가 분모에 들어가는 것은 맞지만, **그 생성자는 항상 커버된다.**
Mockito 의 `@InjectMocks` 든 직접 `new` 든 스프링 주입이든 전부 생성자를 부른다.
생성자만 안 불리고 다른 메서드는 다 불리는 상황이 성립하지 않고, 서비스에 테스트가 아예 없으면 생성자와 무관하게 이미 막힌다.

서비스는 `private final` 의존성만 갖는 무상태 빈이라 Lombok 이 만드는 것도 생성자뿐이다.
`@Getter` 나 `@ToString` 은 서비스에 붙지 않고, 엔티티에 붙더라도 `includes` 가 `*.internal.service.*` 로 좁혀져 있어 분모에 들어오지 않는다.

**설정 파일 하나와 그것을 검사하는 게이트 항목이 아무 일도 하지 않고 있었다.** 그래서 뺐다.
`internal.service` 패키지에 서비스가 아닌 클래스를 두게 되면 그때 다시 검토한다.

private 메서드가 계산된다는 점도 유의한다. 특정 분기에서만 호출되는 private 헬퍼가 있으면 그 분기를 타는 테스트가 필요하다. 즉 METHOD 기준이라도 **private 메서드를 통해서는 간접적으로 경로 검증이 강제된다.**

#### 3.10.4 통합 테스트는 합산하지 않는다

JaCoCo 는 실행마다 별도 `.exec` 파일을 만든다. 게이트는 그중 `test.exec` 만 읽는다.

**합산하면 게이트가 무의미해진다.** 통합 테스트는 계층을 가로질러 실행되므로 서비스 메서드를 지나가기만 해도
커버리지가 찬다. 그 메서드의 분기와 예외 경로를 하나도 확인하지 않고 100% 를 채울 수 있다.
게이트의 목적이 "서비스 로직에 단위 테스트가 있는가" 라면 합산은 그 목적을 지운다.

```gradle
jacocoTestReport {
    // 게이트와 같은 실행 데이터를 읽는다. 다르면 Sonar 수치와 차단 기준이 어긋난다
    executionData.setFrom fileTree(layout.buildDirectory.dir('jacoco')).include('test.exec')
    reports {
        xml.required = true      // SonarQube 가 읽는 형식
    }
}

jacocoTestReport.dependsOn test
```

통합 테스트는 여전히 `check` 에 묶여 있어 깨지면 병합이 막힌다. 커버리지 계산에만 들어가지 않는다.

통합 테스트는 Testcontainers 로 `mysql:8.4` 를 띄운다. 인메모리 DB 를 쓰면 방언과 잠금 동작이 달라 조건부 UPDATE 검증이 성립하지 않는다.

#### 3.10.5 리포트는 전체를 생성한다

게이트는 service 만 보지만 리포트는 전체를 만든다. SonarQube 에 올라가 **판정에는 쓰이지 않되 관찰용으로 표시된다.**

service 외 영역의 커버리지 추세를 보면 어디에 테스트가 부족한지 드러난다. 나중에 게이트 범위를 넓힐 때 근거가 된다.

## 2. 정적 분석 게이트 상세

#### 3.11.1 역할 분리

**커버리지와 정적 분석의 판정 주체가 다르다.**

| 게이트 | 판정 주체 | 조건 |
|--------|-----------|------|
| 커버리지 | Gradle `jacocoTestCoverageVerification` | service 메서드 100% |
| 정적 분석 | SonarQube Quality Gate | **Blocker 0건** |

**SonarQube Quality Gate 에서 커버리지 조건을 제거한다.** SonarQube 는 커버리지 수치를 만들지 않고 JaCoCo 리포트를 읽어 표시할 뿐이다. 판정까지 SonarQube 에 맡기면 기본 게이트값(신규 코드 80% 등)이 Gradle 기준과 충돌한다.

정리하면 SonarQube 는 **버그, 취약점, 코드 스멜만 판정**하고 커버리지는 표시만 한다.

#### 3.11.2 차단 조건

**차단 조건은 신규 `Blocker` 이슈 0개다.**

| 심각도 | 동작 |
|--------|------|
| Blocker | **차단** |
| 그 외 전부 | 경고. PR 코멘트로 표시 |

##### 왜 Blocker 인가

`Blocker` 는 **프로덕션에서 애플리케이션을 망가뜨릴 높은 확률의 버그**를 뜻한다.
병합을 막을 근거가 되는 것은 이 등급뿐이다.

그 아래 등급은 즉시 검토 대상이지 즉시 차단 대상이 아니다.
차단하지 않아도 경고로 PR 코멘트에 남으므로 놓치지 않는다.

**차단 기준을 등급 하나로 좁히는 것이 중요하다.**
차단 대상을 넓히면 병합이 자주 막히고, 곧 강제 병합이 관행이 된다.
그 순간 게이트 자체가 무의미해진다.

##### 더 엄격하게 갈 경우

운영 중 `Blocker` 만으로 부족하다고 판단되면 아래를 추가한다. 셋 다 체계 무관하게 동작한다.

| 표현 | 의미 |
|------|------|
| Security Rating A 유지 | 보안 취약점 기준 |
| Reliability Rating A 유지 | 버그 기준 |
| 신규 코드의 Maintainability Rating A 유지 | 코드 스멜 기준 |

Quality Gate 조건은 설정 화면에서 선택하는 방식이므로 실제 제공되는 목록을 보고 확정한다.

#### 3.11.3 PR 알림

차단과 별개로 PR 에 문제 위치가 표시된다.

| 방식 | 동작 |
|------|------|
| PR 데코레이션 | 문제가 있는 줄에 SonarQube 가 직접 코멘트 |
| 상태 검사 | PR 상단에 Quality Gate 통과 여부 |
| 요약 코멘트 | 신규 이슈 수, 커버리지(표시용), 중복도 |

#### 3.11.4 워크플로

```yaml
- name: Test and Analyze
  run: ./gradlew build integrationTest jacocoTestReport sonar
  env:
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
```

`build` 안에서 `check` 가 돌며 커버리지 게이트가 판정된다. **커버리지 미달이면 이 단계에서 실패해 Sonar 분석까지 가지 않는다.**

`sonar` 태스크 앞에 `jacocoTestReport` 가 와야 커버리지가 함께 올라간다. 순서가 바뀌면 0 으로 표시된다.

#### 3.11.5 브랜치 보호

두 게이트를 강제하는 유일한 수단이다.

```
main 브랜치 보호
  - 직접 push 금지
  - PR 필수
  - 필수 상태 검사: build (JaCoCo 게이트 포함)
  - 필수 상태 검사: SonarQube Quality Gate
```

Git 서버 측 push 훅은 GitHub 가 제공하지 않고, 로컬 `pre-push` 훅은 `--no-verify` 로 우회된다. **push 자체는 자유롭게 하되 main 진입을 막는 구조다.**

#### 3.11.6 긴급 우회와 검토

장애 대응 중에 게이트에 막히면 곤란하다. **관리자 강제 병합을 허용하되 사용 시 사유를 PR 에 남긴다.**

| 장치 | 내용 |
|------|------|
| 강제 병합 시 사유 기록 | PR 에 남긴다 |
| 주 1회 검토 | 강제 병합 건수와 미해결 Blocker 목록 확인 |
| Slack 통지 | 강제 병합 발생 시 `#alerts-deploy` |

**게이트를 계속 우회한다면 기준이 틀린 것이므로 기준을 고쳐야지 우회를 습관화하면 안 된다.** 알람 문서 8절의 "무시하기로 한 알람은 삭제한다" 와 같은 논리다.

---
