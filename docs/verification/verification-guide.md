# 검증 시스템 실행 방법

## 로컬 검증

커밋한 뒤 backend 디렉터리에서 돌린다. **인자를 안 주면 아직 push 하지 않은 커밋 전부**를 본다.

### Claude Code

```
/v-commit
```

### 그 밖의 방법

```bash
./verify.sh                      # 지시문을 화면에 낸다. 쓰는 에이전트에 붙여넣는다
./verify.sh --agent claude       # 지시문을 그 명령에 바로 넘긴다
./verify.sh --agent "gemini -p"  # 임의의 CLI 에 넘긴다
```

`/v-commit` 은 `./verify.sh` 를 부르는 얇은 진입점이라 **둘의 결과가 같다.**

### 범위 바꾸기

| 인자 | 범위 |
|---|---|
| 없음 | 아직 push 하지 않은 커밋 전부 |
| `HEAD` | HEAD 커밋 하나 |
| `HEAD~1` | 그 앞 커밋 하나. git 이 읽는 그대로다 |
| `<SHA>` | 그 커밋 하나 |
| `-n 5` | 최신 5개 |

**ref 는 언제나 git 이 읽는 그대로다.** 개수는 `-n` 이 맡는다.

인자는 두 방식에 똑같이 준다.

```
/v-commit HEAD
./verify.sh HEAD
```

### 항목 범위 넓히기

```
/v-commit --full
```

```bash
./verify.sh --full
```

`--full` 은 커밋이 아니라 **점검 항목**을 넓힌다. 기본은 backend 항목만 보고,
`--full` 이면 common 과 infra 항목까지 본다.

```
/v-commit HEAD               # HEAD 커밋 하나, backend 항목만
/v-commit HEAD --full        # HEAD 커밋 하나, 세 저장소 항목 전부
/v-commit -n 5 --full        # 최신 5개, 세 저장소 항목 전부
```

```bash
./verify.sh HEAD             # 위와 같다. 인자는 양쪽에 똑같이 통한다
./verify.sh HEAD --full
./verify.sh -n 5 --full
```

**기본이 backend 항목만인 이유는 분량이다.** 전부 보면 판정 한 번에 20만 토큰이 넘어간다.

**CI 도 backend 항목만 본다.** 그래서 common 과 infra 기준은 `--full` 로 보지 않으면 아무도 안 본다.
PR 을 올리기 전에 한 번 돌린다.

### 판정할 것이 없으면 바로 끝난다

```
판정할 항목 없음. 판정 대상 파일이 바뀌지 않았다.
다른 저장소 항목까지 보려면 --full 을 준다.
```

문서만 고친 커밋에서 이렇게 나온다. **정상이다.** 자바가 안 바뀌었으면 코드 항목이 위반될 수 없다.

## 무엇이 병합을 막나

| 게이트 | 막나 | 언제 |
|---|---|---|
| **커버리지** service 메서드 100% | **막는다** | PR |
| **SonarQube 신규 Blocker 0건** | **막는다** | PR |
| **다른 팀원 승인 1건** | **막는다** | PR (develop) |
| G-LOCAL (`./verify.sh`) | 안 막는다 | 로컬, 재량 |
| G-PR (CI 의 LLM 판정) | 안 막는다 | PR, 자동 |

**LLM 판정은 막지 않는다.** 재현율이 측정되지 않아 차단 근거로 쓰지 않는다.
지적이 있어도 병합은 되지만, 읽고 판단은 해야 한다.

## 처음이라면

**backend 만 clone 해도 된다.**

```bash
git clone https://github.com/fresh-market/fm-backend.git backend
```

판정 기준 590건 중 317건이 다른 두 저장소에 있다. `./verify.sh` 가 그 둘을 두 단계로 찾는다.

| 순서 | 위치 | 어떻게 다루나 |
|------|------|---------------|
| 1 | 옆에 clone 된 것 | 디렉터리 이름이 아니라 `items.yml` 의 `source` 로 찾는다. **그대로 쓴다** |
| 2 | `~/.cache/llm-verify/` | **원격 최신에 맞춘 뒤** 쓴다 |

없으면 한 번 물어보고 받는다 (약 1.3MB, 공개 저장소라 인증 불필요). 다음부터는 묻지 않는다.

**2번은 판정 전에 항상 원격 최신으로 맞춘다.** 낡은 기준으로 판정하는 일이 없다.
1번은 사용자가 관리하는 저장소이므로 **갱신하지 않는다.** 기준을 바꾸려면 직접 `git pull` 한다.

어느 시점의 기준으로 판정했는지는 결과와 기록에 남는다.

```
기준: common @ dfe9fd9, infra @ aa74a76
```

---

## 이 시스템이 하는 일

코드 품질 점검을 LLM 에게 맡긴다. 점검 항목은 세 저장소에 나뉘어 총 590건이고, 판정 대상은 backend 코드다.

```
커밋하면      로컬에서 판정한다   (./verify.sh 를 쳐야 돈다)
PR 을 열면    CI 에서 gemini 가 본다  (자동)
둘 다         병합을 막지 않는다
```

칠 수 있는 명령은 [verification-commands.md](./verification-commands.md),
지금 무엇을 검증하고 있는지는 [verification-status.md](./verification-status.md),
결과의 실물은 [verification-example.md](./verification-example.md),
무엇이 언제 도는지는 [verification-workflow.md](./verification-workflow.md),
무엇으로 이루어져 있는지는 [verification-architecture.md](./verification-architecture.md),
설계 근거는 [qa-llm-verification.md](https://github.com/fresh-market/.github/blob/main/docs/software-quality/qa-llm-verification.md) 에 있다.

## 로컬에서 도는 순서

```
1  대상 항목 계산        바꾼 파일에 걸리는 앵커 규칙을 본다
2  ./gradlew check      커버리지와 정적 분석. 알림만
3  판정                 활성 항목 전부
4  기록 저장            backend/docs/llm-review/<계정>_<YYYYMMDD-HHMMSS>_llm-review.md
```

**소스가 안 바뀌었으면 2번을 건너뛴다.** 결과가 직전과 같아서다.

작업 중 여러 번 돌려도 된다. 중간 상태에서 위반이 나오는 것은 정상이고,
기록은 초 단위로 구분되어 덮어쓰지 않는다.

## CI

**PR 에서만 돈다.** 할 일은 없다.

| 언제 | 도는가 |
|------|--------|
| PR 생성 | 돈다 |
| PR 에 push (`synchronize`) | 돈다 |
| PR 을 닫았다 다시 열기 | 돈다 |
| main 에 직접 push | 안 돈다. 관리자만 할 수 있고 브랜치 보호가 막는다 |

`registry-check.yml` 은 조건이 하나 더 붙는다. **점검 항목 문서나 `items.yml` 을 건드린 PR 에서만** 돈다.
Java 코드만 고친 PR 에서는 실행조차 되지 않는다.

**G-PR 은 G-BUILD 가 통과해야 돈다.** 빌드가 깨진 PR 에 LLM 을 부르면 판정 한도만 낭비한다.

판정 코멘트는 **돌 때마다 옛 것을 지우고 새로 단다.** 항상 하나만 남고 그것이 최신이다.
지난 판정은 Actions 탭의 실행 기록과 Job Summary 에 남는다.

코멘트는 위반을 둘로 나눈다.

```
이 PR 이 만든 위반    펼쳐서 보여 준다. 이것만 고치면 된다
기존 부채            접어 둔다. 이 PR 의 책임이 아니다
```

## 결과가 어디에 나오나

| 어디서 돌렸나 | 결과 |
|---|---|
| 로컬 | 터미널 화면 |
| 로컬 | `backend/docs/llm-review/<계정>_<YYYYMMDD-HHMMSS>_llm-review.md` |
| CI | PR 의 리뷰 코멘트 (항상 최신 하나) |
| CI | Actions 실행 화면의 **Summary** |

**CI 결과는 저장소에 파일로 남기지 않는다.** PR 코멘트와 Actions 에만 남는다.
코멘트는 판정할 때마다 교체되고, Actions 로그는 보존 기간이 지나면 사라진다.
오래 남겨야 하면 로컬에서 돌려 기록을 만든다.

로컬 기록에는 **어느 저장소의 어느 시점 기준으로 판정했는지**가 함께 남는다.

```
backend/docs/llm-review/
  devjohnpark_20260806-174500_llm-review.md
  teammate_20260807-093011_llm-review.md
```

```markdown
---
검증: G-LOCAL
계정: <계정명>
시각: <ISO 8601>
커밋: <전체 SHA>
범위: <base>..<head>
기준 저장소:
  common: <커밋 SHA>  <경로>
  infra: <커밋 SHA>  <경로>
매칭 규칙: [<규칙 id>]
활성 항목: <n> (backend <a>, common <b>, infra <c>)
---
```

이 디렉터리는 **커밋한다.** 로컬 검증은 재량이라 안 돌려도 아무 일이 없고, 기록이 남아야 돌렸는지가 구분된다.
파일명에 계정과 초 단위 시각이 들어가므로 팀원끼리 충돌하지 않는다.

## 결과 읽기

| 값 | 뜻 | 할 일 |
|---|---|---|
| `VIOLATION` | 위반이다 | 고친다 |
| `OK` | 충족했다 | 없음 |
| `NOT_APPLICABLE` | 이 변경과 무관하다 | 없음 |
| `INSUFFICIENT_EVIDENCE` | 판정할 파일을 못 읽었다 | 반복되면 `anchors.yml` 의 앵커 목록 보강 |
| `CONFLICTING_BASELINE` | 확정값이 문서마다 다르다 | 팀이 어느 쪽인지 정한다 |
| `UNJUDGED` | 물어보지 않았다 | 로컬로 다시 본다 |

`OK` 와 `UNJUDGED` 는 다르다. 통과한 것과 안 본 것을 섞지 않기 위해 나눠 둔다.

LLM 판정이라 오탐이 있고 병합을 막지 않으므로, 틀렸다고 판단되면 그냥 병합해도 된다.

## 무엇이 켜지는지 미리 보기

앵커 규칙을 고쳤을 때 의도한 항목이 켜지는지 확인한다. LLM 을 부르지 않으므로 몇 번이든 돌려도 된다.

```bash
python3 ../common/.github/llm-verify/run.py --mode judge --dry-run \
  --backend . --common ../common --infra ../infra --base HEAD~1 --head HEAD
```

```
매칭 규칙   <걸린 규칙>
활성 항목   <n>건
앵커 파일   읽음 <x>, 부재 <y>, 실패 <z>
확정값      <m>건 또는 불필요
```

**숫자를 여기 적어 두지 않는다.** 항목이 늘거나 앵커 규칙이 바뀌면 값이 달라지는데,
문서에 박아 두면 조용히 어긋난다. 실제 값은 위 명령이 알려 준다.

## 점검 항목을 고쳤을 때

`items.yml` 은 가이드 문서에서 생성된 파생물이다. **문서를 고쳤으면 다시 생성해야 한다.**
안 하면 문서에는 있는데 게이트는 모르는 항목이 생긴다.

```bash
cd ../common/.github/llm-verify

python3 gen_items.py ../../docs/software-quality 'qa-*-guideline.md' common \
        -o items.yml
python3 gen_items.py ../../../backend/docs/code-architecture '*-guideline.md' backend 코드 \
        -o ../../../backend/.github/llm-verify/items.yml
python3 gen_items.py ../../../infra/docs/infra-review '*-guideline.md' infra 코드 \
        -o ../../../infra/.github/llm-verify/items.yml
```

`--check` 를 주면 파일을 쓰지 않고 어긋났는지만 본다.

```
OK  backend 273건. 문서와 레지스트리가 일치한다
```

**재생성을 잊어도 CI 가 잡는다.** 세 저장소 모두 `registry-check.yml` 이 이 검사를 돌린다.
결정론적이라 오탐이 없으므로 **LLM 게이트와 달리 병합을 막는다.**

문서에 적는 형식은 이렇다. 층위 태그는 common 에만 붙인다.
**항목 줄 아래 들여쓴 줄은 판정 기준으로 함께 실린다.**

```markdown
점검 항목
* `[코드]` `SEC-1-07` 세션 고정 공격을 막는가       <- common
  로그인 성공 시 세션 식별자를 새로 발급해야 한다.    <- 판정 기준
* `EJ-1-07` 인스턴스화를 막으려면 private 생성자를 쓰는가   <- backend, infra
```

나머지 필드(`gate`, `domains`, `ci_stage` 등)는 `gen_items.py` 가 규칙으로 채운다.
`level` 에서 `gate` 가 정해지므로 **층위 태그를 잘못 붙이면 판정 시점이 바뀐다.**

## 안 될 때

| 증상 | 조치 |
|---|---|
| 기준 저장소를 받겠다고 묻는다 | 처음 한 번만 묻는다. 승인한다 |
| 받기를 거절해 멈췄다 | 승인하거나, `common` 과 `infra` 를 옆에 clone 한다 |
| 종료 코드 `2` | 기준 저장소를 못 찾았다. 화면의 안내대로 받는다 |
| "원격을 확인하지 못했습니다" | 네트워크가 안 된다. 받아 둔 기준으로 판정한다 |
| 옆 저장소가 낡았다 | 1번은 자동 갱신하지 않는다. 직접 `git pull` 한다 |
| "판정할 항목 없음" | 정상이다. 문서만 고쳤을 때 그렇다 |
| 활성 항목이 적고 규칙이 `on_no_match` 다 | 정상이다. 어떤 앵커에도 안 걸린 변경이다 |
| 같은 항목이 계속 `INSUFFICIENT_EVIDENCE` | `backend/.github/llm-verify/anchors.yml` 의 `anchors` 에 파일 추가 |
| CI 워크플로가 빨갛다 | `GEMINI_API_KEY`, `SONAR_TOKEN` 시크릿 확인 |
| PR 을 열었는데 registry-check 가 안 돈다 | 문서를 건드린 PR 에서만 돈다 |
| `G-PR` 이 skipped 다 | `G-BUILD` 가 실패했다. 그것부터 고친다 |
| 같은 지적이 매 PR 마다 나온다 | `common/.github/llm-verify/known-conflicts.yml` 에 등록 |
