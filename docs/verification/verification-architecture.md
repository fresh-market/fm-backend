# 검증 시스템 구조

무엇으로 이루어져 있고 서로 어떻게 맞물리는지를 정리한다.

| 알고 싶은 것 | 볼 곳 |
|---|---|
| **무엇으로 이루어져 있나** | 이 문서 |
| 어떻게 쓰나 | [verification-guide.md](./verification-guide.md) |
| 칠 수 있는 명령 | [verification-commands.md](./verification-commands.md) |
| 언제 무엇이 도나 | [verification-workflow.md](./verification-workflow.md) |
| 결과가 어떻게 생겼나 | [verification-example.md](./verification-example.md) |
| **지금 무엇을 검증하나** | [verification-status.md](./verification-status.md) (자동 생성) |
| 왜 이렇게 설계했나 | [qa-llm-verification.md](https://github.com/fresh-market/.github/blob/main/docs/software-quality/qa-llm-verification.md) |

## 1. 네 층

```
   1층   가이드 문서            사람이 쓴다. 판정 기준의 원본
          qa-*.md, *-guideline.md
              |
              |  gen_items.py 가 추출
              v
   2층   레지스트리             기계가 읽는다. 문서의 파생물
          items.yml  x 3
              |
              |  anchors.yml 이 무엇을 켤지 정한다
              v
   3층   앵커 규칙              변경 파일 -> 켤 항목
          anchors.yml
              |
              |  run.py 가 조립해 LLM 에 넘긴다
              v
   4층   실행기                 판정하고 출력한다
          run.py, llm-verify.yml, pr-gate.yml
```

**아래층은 위층을 모른다.** `run.py`는 항목이 무슨 뜻인지 모르고 ID 와 제목만 넘긴다.
`anchors.yml`은 항목 본문을 모르고 접두사와 장 번호만 안다.

층을 나눈 이유는 **바뀌는 속도가 다르기 때문**이다.
문서는 자주 바뀌고, 앵커 규칙은 가끔 바뀌고, 실행기는 거의 안 바뀐다.
한 파일에 두면 문서 한 줄을 고칠 때 실행기가 깨진다.

## 2. 저장소 셋

```
fresh-market/.github (common)          fresh-market/fm-backend (backend)         fresh-market/fm-infra
-----------------------         ------------------         -----------
docs/software-quality/          docs/code-architecture/    docs/system-design/
  qa-*.md          217건          *-guideline.md  250건      확정값의 근거
  얼마나 잘 하는가                  어떻게 쓰는가              docs/infra-review/
                                                              *-guideline.md 100건
                                                              이 인프라가 강제하는 것
.github/llm-verify/             .github/llm-verify/        .github/llm-verify/
  items.yml        217            items.yml       250        items.yml       100
  gen_items.py     생성기          anchors.yml     규칙 11
  run.py           실행기
  known-conflicts.yml

.github/workflows/              .github/workflows/         .github/workflows/
  llm-verify.yml   본체            pr-gate.yml     호출자     registry-check.yml
  registry-check.yml               registry-check.yml
  llm-verify/verify.sh           verify.sh        G-LOCAL 진입점
    G-LOCAL 본체                  .claude/commands/
docs/verification/                 v-commit.md    Claude 편의 진입점
  g-local.md       절차
  세 저장소 공용
```

**판정 기준은 셋에서 오지만 판정 대상은 backend 코드 하나다.**
`anchors.yml`이 backend 에만 있기 때문이다. Terraform 이 생기면 infra 에도 만든다.

각자 자기 문서에서 자기 `items.yml`을 만들고, 자기 `registry-check.yml`이 둘의 일치를 지킨다.
**생성기와 실행기는 common 에 하나만 둔다.** 셋으로 복제하면 갈라진다.

## 3. 파일별 역할

아래 줄 수는 **규모를 가늠하라고 적은 것**이지 관리 대상이 아니다. 바뀌어도 고치지 않는다.

### 판정 기준

| 파일 | 줄 | 하는 일 |
|------|----|---------|
| `qa-*.md` (11개) | - | 품질 속성 점검 항목 217건과 근거 |
| `*-guideline.md` (8개) | - | 코드 관용 점검 항목 250건 |
| `infra-review/*-guideline.md` (3개) | - | 인프라 제약 100건 |
| `system-design/*.md` (9개) | 165KB | 확정값의 근거. 판정에는 값만 쓴다 |

### 데이터

| 파일 | 줄 | 하는 일 |
|------|----|---------|
| `common/.github/llm-verify/items.yml` | 224 | 217건. 문서에서 생성 |
| `backend/.github/llm-verify/items.yml` | 257 | 250건 |
| `infra/.github/llm-verify/items.yml` | 107 | 100건 |
| `backend/.github/llm-verify/anchors.yml` | 214 | 규칙 11개. **손으로 관리한다** |
| `common/.github/llm-verify/known-conflicts.yml` | 110 | 확정값 모순 4건, 의도된 이탈 1건 |

`anchors.yml`만 생성물이 아니다. 어떤 파일이 어떤 항목을 켜는지는 사람이 정하는 판단이다.

### 실행

| 파일 | 줄 | 하는 일 |
|------|----|---------|
| `common/.github/llm-verify/run.py` | 722 | 범위 산출, 필터, 입력 수집, LLM 호출, 출력 |
| `common/.github/llm-verify/gen_items.py` | 244 | 문서 -> `items.yml`. `--check` 로 대조 |
| `common/.github/workflows/llm-verify.yml` | 140 | 체크아웃과 코멘트. 재사용 워크플로 |
| `backend/.github/workflows/pr-gate.yml` | 87 | G-BUILD 와 G-PR. needs 로 순서를 매긴다 |
| `*/.github/workflows/registry-check.yml` | 33~42 | 문서와 레지스트리 일치 검사 |
| `<repo>/verify.sh` | 41 | G-LOCAL 진입점. common 을 찾아 본체를 부른다 |
| `common/.github/llm-verify/verify.sh` | 117 | G-LOCAL 본체. 계산과 판정 지시문 생성. LLM 없음 |
| `common/docs/verification/g-local.md` | 219 | G-LOCAL 판정 절차. 도구에 매이지 않는다. 세 저장소가 함께 쓴다 |
| `backend/.claude/commands/v-commit.md` | 16 | Claude Code 편의 진입점. `./verify.sh` 를 부른다 |

## 4. 항목 하나가 판정되기까지

`SEC-1-01`(리소스 접근 시 소유권 검증)을 예로 든다.

```
qa-security-guideline.md 1장
  * `[코드]` `SEC-1-01` 리소스 접근 시 소유권 또는 권한을 검증하는가
        |
        |  gen_items.py
        v
items.yml
  {id: SEC-1-01, doc: qa-security-guideline.md, ch: 1, level: 코드, gate: G-PR,
   gates: [local, ci], ci_stage: 2, domains: [security], title: "..."}
        |
        |  anchors.yml 의 service 규칙이 SEC 1장을 켠다
        v
활성 항목에 포함
        |
        |  run.py 가 qa-security-guideline.md 본문과 앵커 파일을 함께 넘긴다
        v
LLM 판정 -> VIOLATION / OK / NOT_APPLICABLE / ...
        |
        |  defers_to 억제, 신규와 기존 분리
        v
PR 코멘트
```

**문서의 한 줄이 판정 한 건이 된다.** 중간에 사람이 옮겨 적는 곳이 없다.

## 5. 항목의 메타데이터

`items.yml`의 각 필드가 어디에 쓰이는지.

| 필드 | 어디서 나오나 | 무엇에 쓰이나 |
|------|---------------|---------------|
| `id` | 문서 | 식별, `defers_to` 대상 |
| `title` | 문서 | 프롬프트 |
| `doc` | 파일명 | 판정 기준 본문 로드 |
| `ch` | ID 의 가운데 숫자 | `chapters` 필터 |
| `level` | 문서의 `[코드]` 태그 | `levels` 필터 |
| `gate` | `level` 에서 파생 | **지금은 쓰이지 않는다.** 메타데이터 |
| `ci_stage` | 저장소 | 1단계와 2단계 분할 |
| `domains` | 접두사 매핑 | 집계와 검색 |
| `defers_to` | 명시 목록 | 중복 지적 억제 |
| `level_verified` | 층위 태그 유무 | backend, infra 350건이 `false` |

**`gate`는 필터에 쓰이지 않는다.** 판정 시점은 `anchors.yml`의 `levels`가 정한다.
`gate`는 "이 항목을 어느 게이트가 봐야 하는가"를 기록해 둔 것이고, 현재 구현은 그것을 강제하지 않는다.

## 6. 무엇을 고치면 무엇이 바뀌나

| 고치는 것 | 영향 | 재생성 필요 |
|-----------|------|-------------|
| `qa-*.md`의 점검 항목 | 판정 항목이 늘거나 준다 | **`gen_items.py` 실행** |
| `qa-*.md`의 층위 태그 | `gate`와 `levels` 필터 결과 | **`gen_items.py` 실행** |
| `qa-*.md`의 본문 | 판정 기준이 바뀐다 | 불필요 |
| `anchors.yml`의 `trigger` | 어떤 파일이 규칙을 켜는가 | 불필요 |
| `anchors.yml`의 `activate` | 몇 건이 켜지는가 | 불필요 |
| `anchors.yml`의 `anchors` | 함께 읽는 파일 | 불필요 |
| `known-conflicts.yml` | 유보와 의도된 이탈 처리 | 불필요 |
| `run.py` | 판정 절차 전체 | 불필요 |

**앞의 둘만 재생성이 필요하고, 잊으면 `registry-check.yml`이 PR 을 막는다.**

바꾸기 전에 무엇이 켜지는지 보려면 LLM 을 부르지 않는 모드를 쓴다.

```bash
python3 ../common/.github/llm-verify/run.py --mode judge --dry-run \
  --backend . --common ../common --infra ../infra --base HEAD~1 --head HEAD
```

## 7. 확장하는 곳

| 하고 싶은 것 | 어디를 고치나 |
|--------------|---------------|
| 점검 항목 추가 | 가이드 문서에 한 줄 + `gen_items.py` 실행 |
| 새 파일 유형을 판정 대상으로 | `anchors.yml`에 규칙 추가 |
| 판정 근거 파일 보강 | 해당 규칙의 `anchors` |
| 중복 지적 억제 | `gen_items.py`의 `DEFERS` |
| 모델 교체 | `run.py`의 `MODEL`, `ENDPOINT` |
| 다른 저장소도 판정 대상으로 | 그 저장소에 `anchors.yml`과 호출자 워크플로 |

마지막이 가장 크다. 지금은 backend 만 판정 대상이라 `anchors.yml`이 하나뿐이다.
infra 에 Terraform 이 생기면 그쪽에도 만들고, 그때 이 문서를 common 으로 옮긴다.

## 8. 지금 비어 있는 곳

| 없는 것 | 결과 |
|---------|------|
| `backend/src`의 Java 코드 | 모든 규칙이 기본 집합으로 떨어진다 |
| `backend/build.gradle` | **G-BUILD 가 돌지 않는다.** 차단 게이트 하나가 공백 |
| `GEMINI_API_KEY` | CI 판정 스텝이 실패한다 |
| `infra/.github/llm-verify/anchors.yml` | 인프라 코드 자체는 판정되지 않는다 |
| G-RELEASE 스크립트 | 배포 전 `PRE` 13건이 확인되지 않는다 |

**1~4층 전부와 데이터는 준비되어 있고, 판정 대상이 없는 상태다.**

## 9. 설계와 구현이 다른 곳

문서에 적힌 설계와 `run.py`의 구현이 갈라진 곳을 기록해 둔다. 모르고 쓰면 오해한다.

| 설계 | 구현 | 이유 |
|------|------|------|
| base 커밋에 재판정해 신규와 기존을 가른다 | 근거 줄이 이 PR 이 추가한 줄인지 diff 로 대조 | LLM 호출이 두 배가 된다 |
| 판정 결과를 캐시해 증분만 재판정 | 매 push 마다 전량 재판정 | 미구현 |
| `gate`로 판정 시점을 강제 | `levels`로만 필터 | `gate`가 참조되지 않는다 |

첫 번째는 **줄을 지워서 생긴 신규 위반이 기존 부채로 분류되는** 한계가 있다.
