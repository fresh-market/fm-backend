# 빌드 게이트 (guideline)

`build.gradle` 과 `settings.gradle` 을 고치는 PR에 적용한다.
설계 근거는 [build-gate-rationale.md](./build-gate-rationale.md) 에 있다.

**이 문서의 항목은 LLM이 아니라 도구가 판정한다.** 결정론적이므로 병합을 차단해도 근거가 있다.

| 게이트 | 판정 주체 | 조건 | 동작 |
|--------|-----------|------|------|
| 커버리지 | Gradle `jacocoTestCoverageVerification` | `*.internal.service.*` 메서드 100% | **병합 차단** |
| 정적 분석 | SonarQube Quality Gate | **신규 Blocker 이슈 0건** | **병합 차단** |

## 1. 커버리지

점검 항목
* `BLD-1-01` JaCoCo 대상이 `*.internal.service.*`로 좁혀져 있는가
  `includes`로 좁히므로 exclude 목록이 필요 없다. config, dto, entity, Q클래스가 자동으로 빠진다. 패키지 전체가 대상이며, 그 안에 `~Service`만 두도록 `DPB-4-10`이 막으므로 대상에서 빠지는 클래스가 생기지 않는다.
* `BLD-1-02` 판정 단위가 클래스별(`element = 'CLASS'`), 카운터가 메서드(`counter = 'METHOD'`)인가
* `BLD-1-03` 기준이 `minimum = 1.00`인가
* `BLD-1-04` 커버리지 판정이 단위 테스트의 `.exec`만 읽는가
  통합 테스트를 합산하면 계층을 가로질러 메서드를 지나가기만 해도 커버리지가 차서, 서비스 로직을 단위 테스트 없이 통과시킬 수 있다.
* `BLD-1-05` `check`가 `integrationTest`와 `jacocoTestCoverageVerification`에 의존하는가
* `BLD-1-06` `jacocoTestReport`가 `sonar` 태스크보다 먼저 도는가
  순서가 바뀌면 SonarQube에 커버리지가 0으로 표시된다.
* `BLD-1-07` 검증 대상 클래스가 있는데 실행 데이터가 없는 상태를 막는가
  `jacocoTestCoverageVerification`은 `.exec`가 하나도 없으면 Gradle이 태스크를 통째로 건너뛴다.
  건너뛴 것은 초록으로 보이므로, 테스트를 하나도 안 쓰면 무사통과하고 하나라도 쓰면 100%를 요구하는 거꾸로 걸린 게이트가 된다.
  대상 클래스의 존재와 실행 데이터의 존재를 따로 확인해 이 구간을 막는다.

## 2. 정적 분석

점검 항목
* `BLD-2-01` SonarQube Quality Gate가 커버리지를 판정하지 않는가
  판정 주체가 둘이면 기본 게이트값(신규 코드 80% 등)이 Gradle 기준과 충돌한다.
  무료 플랜은 커스텀 게이트를 만들 수 없어 조건을 뺄 수 없으므로, `sonar.qualitygate.wait` 을 켜지 않는 것으로 대신한다.
* `BLD-2-02` 정적 분석 차단 조건이 신규 `Blocker` 이슈 0건인가
  `Blocker`는 프로덕션에서 애플리케이션을 망가뜨릴 높은 확률의 버그를 뜻한다. 병합을 막을 근거가 되는 것은 이 등급뿐이다.
  그 아래 등급은 차단하지 않고 경고로만 표시한다.
  차단은 워크플로가 이슈 검색 API 로 신규 `Blocker` 수를 직접 세어 수행한다.
* `BLD-2-03` 브랜치 보호의 필수 상태 검사에 `G-BUILD`가 등록되어 있는가
  커버리지와 정적 분석은 한 잡(`G-BUILD`) 안에서 함께 돌므로 등록되는 검사 이름은 하나다. 이것이 두 기준을 강제하는 유일한 수단이다.
  `G-PR`(LLM 판정)은 일부러 등록하지 않는다. 재현율이 측정되지 않은 판정으로 병합을 막으면 오탐이 쌓여 우회 문화가 생긴다.

### 무료 플랜에서 차단하는 방법

커스텀 Quality Gate 는 Team 플랜부터 쓸 수 있다. 무료 플랜은 내장 `Sonar way` 뿐인데
거기에는 신규 코드 커버리지 80% 조건이 들어 있어 그대로 켜면 커버리지 판정이 둘이 된다.

그래서 `sonar.qualitygate.wait` 을 쓰지 않고 이슈 검색 API 로 신규 `Blocker` 만 센다.
이 API 는 무료 플랜에서도 동작하며, 세는 대상이 정확히 `BLD-2-02` 가 요구하는 것이다.

```bash
curl -u "$SONAR_TOKEN:" \
  "https://sonarcloud.io/api/issues/search?componentKeys=<키>&severities=BLOCKER&resolved=false&pullRequest=<번호>"
```

Team 플랜으로 올리면 커스텀 게이트에서 커버리지 조건을 빼고 `qualitygate.wait` 을 켜는 편이 낫다.
그때는 이 단계를 지운다.

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

### 2.1 100% 기준과 "커버리지를 목표로 삼지 말라"는 원칙의 관계

backend `unit-testing-guideline.md`의 `UT-6-03`은 "커버리지 숫자 자체를 목표로 삼지 않는가"를 묻는다.
표면상 100% 강제와 충돌해 보이지만 **재는 것이 다르다.**

| | 재는 것 | 보장하는 것 |
|---|---|---|
| METHOD 100% | **범위** | 모든 메서드에 테스트가 한 번은 지나갔다 |
| `UT-6-03` | **깊이** | 그 테스트가 실제로 검증하는가 |

METHOD 카운터는 메서드가 호출되었는지만 본다. 100줄 중 1줄만 지나가도 커버된 것으로 계산된다.

```java
public void placeOrder(OrderCommand cmd) {
    validate(cmd);              // 여기서 예외 발생
    stockService.deduct(cmd);   // 실행 안 됨
    orderRepository.save(...);  // 실행 안 됨
}
```

**실패 경로만 테스트해도 이 메서드는 100%로 계산된다.**
그래서 깊이 검증은 게이트가 아니라 코드 리뷰와 테스트 작성 규칙이 맡는다. 두 항목은 역할이 겹치지 않는다.

특히 **조건부 UPDATE의 `affected rows == 0` 분기는 정합성 최종 방어선(INF-1-05)이므로 반드시 실패 경로 테스트를 함께 작성한다.**

### 2.2 로컬에서도 같은 게이트를 돌린다

push 전에 `./gradlew check`로 확인한다. CI에서 처음 알면 이미 PR을 연 뒤다.

**기준이 100%라 여유가 없다.** 새 메서드를 하나 추가하고 테스트를 빠뜨리면 그 순간부터 모든 병합이 막힌다.
의도된 엄격함이지만, 로컬에서 먼저 돌리지 않으면 CI 실패로 알게 되어 왕복이 생긴다.


## 3. 관련 문서

* 설계 근거: [build-gate-rationale.md](./build-gate-rationale.md)
* 기술 스택: [tech-stack.md](../tech-stack.md)
* 인프라 제약: `fresh-market/fm-infra` 의 `docs/infra-review/code-guideline.md`
