# 검증기 명령어

칠 수 있는 명령을 한곳에 모은다. 경로는 저장소 셋의 부모 디렉터리 기준이다.

```
어딘가/            <- 여기서 친다고 가정
  common/
  backend/
  infra/
```

`backend` 안에서 치려면 `../common/...` 처럼 앞에 `../` 를 붙인다.

## 한 눈에

| 무엇 | 명령 | API 키 | 파일을 고치나 |
|---|---|---|---|
| 로컬 검증 | `/v-commit` | 아니오 | 기록을 남긴다 |
| 로컬 검증 (다른 저장소 항목까지) | `/v-commit --full` | 아니오 | 기록을 남긴다 |
| 로컬 검증 (다른 CLI) | `./verify.sh --agent "<명령>"` | 아니오 | 기록을 남긴다 |
| 무엇이 켜지는지 | `run.py --mode judge --dry-run` | 아니오 | 아니오 |
| 어떤 규칙이 걸렸는지 | `run.py --mode match` | 아니오 | 아니오 |
| 실제 판정 | `run.py --mode judge` | **예** | 결과 파일 |
| 레지스트리 재생성 | `gen_items.py -o` | 아니오 | **예** |
| 레지스트리 검사 | `gen_items.py --check` | 아니오 | 아니오 |
| 현황 문서 생성 | `gen_status.py -o` | 아니오 | **예** |
| 빌드 게이트 | `./gradlew check` | 아니오 | 아니오 |

**API 키가 필요 없는 것부터 쓴다.** 대부분의 작업은 `--dry-run` 과 `--check` 로 끝난다.

---

## 1. 로컬 검증

backend 디렉터리에서 Claude Code 를 띄운 뒤 친다.

```
/v-commit
/v-commit HEAD
/v-commit HEAD~1
/v-commit 84fa77d
/v-commit -n 5
/v-commit --full
/v-commit HEAD --full
```

| 인자 | 범위 |
|---|---|
| 없음 | 아직 push 하지 않은 커밋 전부 |
| `HEAD` | HEAD 커밋 하나 |
| `HEAD~1` | 그 앞 커밋 하나. git 이 읽는 그대로다 |
| `<SHA>` | 그 커밋 하나 |
| `-n 5` | 최신 5개 |
| `--full` | 커밋 범위가 아니라 점검 항목을 넓힌다. 아래를 본다 |

**ref 는 언제나 git 이 읽는 그대로다.** 개수는 `-n` 이 맡는다.
빌드 게이트 확인, 판정, `backend/docs/llm-review/` 에 기록 저장까지 한다.

`common` 이나 `infra` 가 옆에 없으면 0단계에서 멈춘다.

### 판정 범위는 기본이 좁다

**backend 에서 돌리면 backend 항목만 본다.** 어느 것이 자기 것인지는 `items.yml` 의 `source` 가 밝힌다.
common 과 infra 항목은 `--full` 로 연다. 커밋 범위를 정하는 ref 와는 축이 다르므로 함께 쓴다.

```
/v-commit                  push 하지 않은 커밋 전부, backend 항목만
/v-commit --full           push 하지 않은 커밋 전부, 세 저장소 항목 전부
/v-commit HEAD --full      HEAD 커밋 하나, 세 저장소 항목 전부
/v-commit 84fa77d --full   그 커밋 하나, 세 저장소 항목 전부
/v-commit -n 5 --full      최신 5개, 세 저장소 항목 전부
```

기본을 좁게 둔 이유는 비용이다. 전부 보면 기준 문서 12개에 확정값까지 읽어야 해서
**판정 한 번에 20만 토큰이 넘어간다.** 작업 중 반복 실행하는 도구라 그러면 쓸 수가 없다.

`--full` 은 **PR 을 올리기 전에** 쓴다. CI 의 G-PR 은 1단계에서 backend 항목만 보므로,
**common 과 infra 기준은 여기서 `--full` 로 보지 않으면 아무도 안 본다.**

무엇이 몇 건 켜지는지는 판정 지시문의 `계산 결과` 줄에 찍힌다.

```json
{"active": "308", "source": "backend", "own": "180", "other": "128"}
```

`own` 이 기본으로 판정하는 범위이고, `other` 가 `--full` 로 열리는 몫이다.
근거와 계산 규칙은 common 저장소의 `docs/verification/g-local.md` 1장에 있다.

### 다른 CLI 에이전트로 돌리기

`/v-commit` 은 Claude Code 편의 진입점일 뿐이다. 다른 에이전트를 쓰면 `./verify.sh` 를 직접 돌린다.
`--agent <명령>` 을 주면 판정 지시문을 그 명령의 stdin 으로 바로 넘긴다.

```bash
# 아직 push 하지 않은 커밋 전부
./verify.sh --agent "claude -p"

# HEAD 커밋 하나
./verify.sh HEAD --agent "claude -p"

# 특정 커밋 하나. ref 는 git 이 읽는 그대로다
./verify.sh 84fa77d --agent "claude -p"
./verify.sh HEAD~1 --agent "gemini -p"

# 최신 5개
./verify.sh -n 5 --agent "claude -p"

# 다른 저장소 항목까지 판정한다. ref 와 함께 쓴다
./verify.sh --full --agent "codex exec"
./verify.sh HEAD --full --agent "claude -p"
./verify.sh 84fa77d --full --agent "gemini -p"
```

**조건은 하나다.** stdin 을 읽어 비대화식으로 도는 명령이어야 한다.
따옴표 없이 전개되므로 `"gemini -p"` 처럼 인자를 붙인 명령도 그대로 쓸 수 있다.

`--agent` 를 빼면 지시문을 화면에 출력만 한다. 쓰는 에이전트에 붙여넣어도 결과는 같다.

```bash
./verify.sh
./verify.sh HEAD
./verify.sh HEAD~1
./verify.sh 84fa77d
./verify.sh -n 5
./verify.sh --full
./verify.sh HEAD --full
```

`./verify.sh --help` 가 이 목록을 그대로 뿌린다. 본체의 사용법 헤더를 읽어 내는 것이라 항상 최신이다.

기록 저장은 지시문이 시키는 일이므로 어느 쪽으로 돌려도 `backend/docs/llm-review/` 에 남는다.

## 2. 무엇이 켜지는지 보기

앵커 규칙을 고쳤을 때 의도한 항목이 켜지는지 확인한다. **LLM 을 부르지 않으므로 몇 번이든 돌려도 된다.**

```bash
python3 common/.github/llm-verify/run.py --mode judge --dry-run \
  --backend backend --common common --infra infra \
  --base HEAD~1 --head HEAD
```

```
매칭 규칙   <걸린 규칙>
활성 항목   <n>건
  1단계    <a>건
  2단계    <b>건
  저장소별  {'backend': <x>, 'common': <y>, 'infra': <z>}
앵커 파일   읽음 <p>, 부재 <q>, 실패 <r>
기준 문서   <k>건
확정값      <m>건 또는 불필요
모순 유보   <c>건
의도된 이탈 <d>건
프롬프트 1단계  <문자 수>자 (대략 <토큰 수> 토큰)
```

**부재와 실패는 다르다.** 부재는 그 경로에 파일이 없다는 확인이라 판정 근거가 되고,
실패는 있는데 못 읽은 것이라 `INSUFFICIENT_EVIDENCE` 사유가 된다.

## 3. 어떤 규칙이 걸렸는지만 보기

CI 가 infra 체크아웃 여부를 정할 때 쓰는 모드다. 진단용으로도 쓴다.

```bash
python3 common/.github/llm-verify/run.py --mode match \
  --backend backend --base HEAD~1 --head HEAD
```

```json
{"needs_baseline": "true", "rules": "service", "changed": "1"}
```

## 4. 실제 판정

CI 가 부르는 것과 같다. **CI 가 실패했을 때 로컬에서 재현하는 용도다.**

```bash
GEMINI_API_KEY=... python3 common/.github/llm-verify/run.py --mode judge \
  --backend backend --common common --infra infra \
  --base <base SHA> --head <head SHA> \
  --out verify-out.md
```

키가 없으면 종료 코드 1 이다.
`--out` 이 만드는 파일이 PR 코멘트로 올라가는 내용 그대로다.

## 5. 레지스트리 재생성

**가이드 문서의 점검 항목이나 층위 태그를 고쳤으면 반드시 돌린다.**
안 돌리면 문서에는 있는데 게이트는 모르는 항목이 생긴다.

```bash
cd common/.github/llm-verify

python3 gen_items.py ../../docs/software-quality 'qa-*.md' common \
        -o items.yml
python3 gen_items.py ../../../backend/docs/code-architecture '*-guideline.md' backend 코드 \
        -o ../../../backend/.github/llm-verify/items.yml
python3 gen_items.py ../../../infra/docs/infra-review '*-guideline.md' infra 코드 \
        -o ../../../infra/.github/llm-verify/items.yml
```

`-o` 를 빼면 표준 출력으로 낸다.

## 6. 레지스트리 검사

문서와 레지스트리가 어긋났는지만 본다. **파일을 고치지 않는다.**

```bash
python3 gen_items.py ../../docs/software-quality 'qa-*.md' common \
        -o items.yml --check
```

```
OK  common 217건. 문서와 레지스트리가 일치한다
```

어긋나면 무엇이 다른지 diff 를 찍고 **종료 코드 1** 을 낸다.
세 저장소의 `registry-check.yml` 이 PR 에서 이것을 돌리므로, 재생성을 잊으면 병합이 막힌다.

## 7. 현황 문서 생성

지금 무엇이 켜지고 무엇이 막혀 있는지를 계산해 문서로 낸다.

```bash
python3 .github/llm-verify/gen_status.py \
        --backend ../fm-backend --common . --infra ../fm-infra \
        -o ../fm-backend/docs/verification/verification-status.md
```

항목을 추가하거나 앵커 규칙을 고친 뒤에 돌린다.
결과는 [verification-status.md](./verification-status.md) 에서 본다.

## 8. 빌드 게이트

Gradle 의 표준 검증 태스크다. LLM 과 무관하다.

```bash
cd backend
./gradlew check
```

```
check
  |- test                              단위 테스트
  |- integrationTest                   통합 테스트
  +- jacocoTestCoverageVerification    *.internal.service.* 메서드 100% 미달이면 실패
```

**병합을 막는 유일한 코드 게이트다.** LLM 판정은 차단하지 않는다.

정적 분석(SonarQube 신규 Blocker 0건)은 CI 에서 따로 돈다.

> **지금은 `backend/build.gradle` 이 없어 이 명령이 돌지 않는다.**
> 필요한 설정은 `infra/docs/infra-review/code-guideline.md` 10장에 예제까지 있다.

---

## 언제 무엇을 치나

```
문서의 점검 항목을 고쳤다
  -> 5. 재생성  ->  6. 검사로 확인

앵커 규칙을 고쳤다
  -> 2. --dry-run 으로 의도한 항목이 켜지는지 확인

커밋했다
  -> 1. /v-commit

CI 가 이상하다
  -> 3. --mode match 로 규칙 확인  ->  4. 실제 판정으로 재현

지금 상태가 궁금하다
  -> 7. 현황 문서 생성
```

## 실패했을 때

| 증상 | 원인 | 조치 |
|---|---|---|
| `GEMINI_API_KEY 가 없다` | 키 미설정 | 4번은 키가 필요하다. 2번을 쓴다 |
| `어긋난다. gen_items.py 를 -o 로 다시 돌려라` | 문서를 고치고 재생성 안 함 | 5번 실행 |
| `층위를 알 수 없다` | 문서에 `[코드]` 태그가 없는데 기본값도 안 줌 | 인자 끝에 층위를 준다 |
| `ID 중복` | 같은 항목 ID 가 두 문서에 있다 | 문서에서 하나를 고친다 |
| `DOMAINS 에 없는 접두사` | 새 접두사를 만들었다 | `gen_items.py` 의 `DOMAINS` 에 추가 |
| 활성 항목이 유난히 적다 | 어떤 규칙도 안 걸렸다 | 정상이다. 문서만 고쳤을 때 그렇다 |

## 관련 문서

* 실행 방법과 준비: [verification-guide.md](./verification-guide.md)
* 무엇으로 이루어져 있나: [verification-architecture.md](./verification-architecture.md)
* 언제 무엇이 도나: [verification-workflow.md](./verification-workflow.md)
* 결과가 어떻게 생겼나: [verification-example.md](./verification-example.md)
* 지금 무엇을 검증하나: [verification-status.md](./verification-status.md)
