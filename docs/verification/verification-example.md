# 검증 결과 예시

판정값 표만 보면 실제로 무엇이 나오는지 알기 어렵다. 이 문서는 결과의 실물을 보여 준다.

실행 방법은 [verification-guide.md](./verification-guide.md), 흐름은 [verification-workflow.md](./verification-workflow.md) 에 있다.

> **아래 건수는 특정 시점의 스냅숏이다.**
> 항목이 늘거나 앵커 규칙이 바뀌면 달라지므로, 지금 값은 `--dry-run` 으로 확인한다.
> 이 문서의 목적은 **출력의 모양**을 보여 주는 것이지 건수를 알려 주는 것이 아니다.
>
> 아래 출력은 `run.py` 를 실제로 돌려서 뽑았다.
> LLM 응답만 대본으로 넣었고 범위 산출, 항목 필터, 신규와 기존 분리, 집계, 렌더링은 전부 실제 코드가 만든 것이다.
> 따라서 건수와 형식은 실제와 같고 판정 내용만 지어낸 것이다.

가정한 상황은 `OrderService.java` 하나를 추가하는 커밋이다.

```java
package com.x.domain.service;
public class OrderService {
    public void pay(Long id) {
        var o = repo.findById(id);
        o.setTotal(o.getTotal() + 1);
        externalApi.call();
    }
}
```

## 1. CI 결과 (정상)

PR 에 코멘트 하나로 달린다. push 할 때마다 새로 달리지 않고 같은 것이 갱신된다.

---

### LLM 검증 (G-PR)

매칭된 규칙 `service`
활성 항목 **179건**  (backend 79, common 85, infra 15)

* 1단계 완료 79건
* 2단계 완료 100건

#### 이 PR 이 만든 위반 3건

**`EJ-7-01`** 파라미터 유효성을 메서드 시작 부분에서 검사하는가 (아이템 49)

* `src/main/java/com/x/internal/service/OrderService.java:3`
* public 메서드가 매개변수 유효성을 검사하지 않는다
* 고치기: 메서드 앞에서 null 과 범위를 검사하고 `IllegalArgumentException` 을 던진다

**`DI-4-02`** 트랜잭션 안에서 외부 API를 호출하지 않는가

* `src/main/java/com/x/internal/service/OrderService.java:6`
* 결제 트랜잭션 안에서 `externalApi.call()` 을 호출한다
* 고치기: 외부 호출을 트랜잭션 밖으로 빼고 실패 시 보상 경로를 둔다

**`SEC-1-01`** 리소스 접근 시 소유권 또는 권한을 검증하는가

* `src/main/java/com/x/internal/service/OrderService.java:4`
* id 로 조회만 하고 호출자가 소유자인지 확인하지 않는다
* 고치기: 인증 주체의 식별자를 조회 조건에 포함한다

<details><summary>기존 부채 1건</summary>

* `DI-2-01` 갱신 손실 가능성이 있는 흐름에 잠금을 적용했는가  `Order.java`

</details>

<details><summary>확정값 모순으로 유보 1건</summary>

* `REL-2-01` 확정값이 다르다. 앱과DB 1.1절은 공유 의존성 제외, 기술스택 4.2절은 `include: db`

</details>

<details><summary>증거 부족으로 판정 못함 2건</summary>

* `INF-1-03` TTL이 배치 주기보다 짧은가
  * `@Scheduled` 설정을 입력에서 찾지 못했다
* `INF-1-07` 락 획득 트랜잭션이 작업 트랜잭션과 분리됐는가
  * 락 구현 파일을 입력에서 찾지 못했다

같은 항목이 매번 여기 나오면 판정이 어려운 코드가 아니라 `anchors.yml` 의 앵커 목록이 부족한 것이다.

</details>

<details><summary>미판정 4건</summary>

**통과가 아니다.** 물어보지 않았거나 응답이 오지 않은 항목이다. 로컬에서 `/v-commit` 을 돌리면 전부 판정된다.

* `INF-2-03` 훅이 실패해도 조건부 UPDATE 방어가 남아 있는가
* `INF-8-01` 누적 값 테이블에 변경 이력이 함께 남는가

</details>

| verdict | 건수 |
|---|---:|
| `VIOLATION` | 4 |
| `OK` | 117 |
| `NOT_APPLICABLE` | 59 |
| `INSUFFICIENT_EVIDENCE` | 1 |
| `CONFLICTING_BASELINE` | 1 |
| `UNJUDGED` | 0 |

저장소에 없는 앵커 경로 3건 (부재 판정의 근거로 썼다): `**/*Api.java`, `**/config/SecurityConfig.java`, `**/internal/client/**/*.java`

이 게이트는 **병합을 막지 않는다.**

---

### 읽는 법

위에서 아래로 읽는다. **맨 위 3건만 이 PR 의 책임이다.**

```
이 PR 이 만든 위반    근거의 줄이 이 PR 이 추가한 줄이다   고친다
기존 부채            원래 있던 것이다                    접혀 있다
확정값 모순으로 유보   판정하지 않았다                     팀이 확정값을 정한다
증거 부족            판정하지 못했다                     앵커 규칙을 보강한다
미판정               물어보지도 않았다                   로컬로 다시 본다
```

**뒤의 셋은 코드가 잘못됐다는 뜻이 아니다.** 시스템이 판단하지 못했다는 뜻이다.
`OK` 와 `NOT_APPLICABLE` 은 집계표에 숫자로만 나온다. **잘한 것을 짚어 주지는 않는다.**

`SEC-1-01` 이 잡힌 것은 `service` 규칙에 `SEC` 를 켜 두었기 때문이다.
컨트롤러만 트리거로 두면 **컨트롤러를 건드리지 않고 서비스에 권한 검사 없는 조회를 추가할 때 아무도 지적하지 않는다.**

## 2. CI 결과 (저하)

1단계 응답이 온전하지 않으면 2단계 전체가 미판정으로 넘어간다.

```
- 1단계 부분 응답: 응답 77 / 요청 79  -> 누락분 UNJUDGED
- 2단계 실패: 1단계가 온전하지 않아 건너뜀  -> 해당 항목 UNJUDGED

### 이 PR 이 만든 위반 없음

| verdict             | 건수 |
| VIOLATION            |   0 |
| OK                   |  51 |
| NOT_APPLICABLE       |  26 |
| UNJUDGED             | 105 |
```

**"위반 없음" 과 "안 봄" 이 구분된다.**
`UNJUDGED` 105건은 통과가 아니다. 이 상태를 보면 로컬에서 `/v-commit` 을 돌려 나머지를 봐야 한다.

이 구분이 없으면 게이트가 통과시킨 것과 물어보지 않은 것이 뒤섞여 "검증했다" 는 말이 의미를 잃는다.

## 3. 로컬 결과 (`/v-commit`)

터미널에 이 형태로 나오고, 같은 내용이 파일로도 저장된다.

```
G-LOCAL  bd07e1a  결제 서비스 추가

빌드 게이트
  커버리지   미달 2건
    OrderService.pay        0%
    OrderService.cancel     0%
  정적 분석  Blocker 0건

매칭된 규칙  service
활성 항목    179건  (backend 79, common 85, infra 15)

VIOLATION 4건
  EJ-7-01   파라미터 유효성을 메서드 시작 부분에서 검사하는가
    OrderService.java:3
    public 메서드가 매개변수 유효성을 검사하지 않는다
    메서드 앞에서 null 과 범위를 검사한다

  DI-4-02   트랜잭션 안에서 외부 API를 호출하지 않는가
    OrderService.java:6
    결제 트랜잭션 안에서 externalApi.call() 을 호출한다
    외부 호출을 트랜잭션 밖으로 뺀다

  SEC-1-01  리소스 접근 시 소유권 또는 권한을 검증하는가
    OrderService.java:4
    id 로 조회만 하고 호출자가 소유자인지 확인하지 않는다
    인증 주체의 식별자를 조회 조건에 포함한다

  DI-2-01   갱신 손실 가능성이 있는 흐름에 잠금을 적용했는가
    Order.java:2
    total 을 읽고 더해 쓰는 흐름에 잠금이 없다
    조건부 UPDATE 로 바꾼다

CONFLICTING_BASELINE 1건
  REL-2-01  헬스체크가 의존 컴포넌트 상태를 반영하는가
    앱과DB 1.1절: 공유 의존성 제외
    기술스택 4.2절: include: db, diskSpace
    -> 결정 필요

INSUFFICIENT_EVIDENCE 1건
  INF-1-07  락 획득 트랜잭션이 작업 트랜잭션과 분리됐는가

OK 117  NOT_APPLICABLE 59

기록: docs/llm-review/devjohnpark_20260806-174500_llm-review.md
```

CI 와 다른 점은 셋이다.

```
UNJUDGED 가 없다          단계를 나누지 않고 활성 항목을 전부 판정한다
빌드 게이트가 함께 나온다   ./gradlew check 를 먼저 돌린다
파일로 남는다             docs/llm-review/ 에 커밋된다
신규와 기존을 안 가른다     커밋 하나만 보므로 가를 것이 없다
```

## 4. 두 결과의 성격

| | 로컬 | CI |
|---|---|---|
| 범위 | push 하지 않은 커밋 (인자로 조절) | PR 누적 |
| 신규와 기존 구분 | 안 함 | 함 |
| 미판정 | 안 생김 | 생길 수 있음 |
| 보존 | 저장소에 커밋 | PR 코멘트와 Actions Summary |

**로컬이 더 완전하고 CI 가 더 넓다.**
로컬은 커밋 하나만 보지만 빠뜨리지 않고, CI 는 PR 전체를 보지만 2단계까지 못 갈 수 있다.
