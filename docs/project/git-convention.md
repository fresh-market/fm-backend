# Git 규약

## 작업 사이클

```bash
git switch develop && git pull
git switch -c feat/order-cancel

# 작업하고 커밋
git commit -m "[Feat] 주문 취소 시 재고를 되돌린다"

./gradlew check      # 이것이 통과해야 병합된다
/v-commit            # 점검 항목 판정이며 PR 를 막지는 않는다. Claude CLI 모드에서 실행시킨다

git push -u origin feat/order-cancel
gh pr create --base develop
```

`/v-commit` 은 Claude Code 안에서만 동작한다. 팀원이 다른 CLI 를 쓰면 `./verify.sh --agent <명령>` 을 쓴다.

## 브랜치

팀원은 `develop` 에서 딴다. **팀원은 `main` 에 직접 못 올린다** (관리자만).

```
feat/     기능 추가
fix/      버그 수정
refactor/ 동작은 그대로, 구조만
docs/     문서만
test/     테스트만
chore/    빌드, 설정, 의존성
```

팀원은 슬래시를 하나만 쓴다. **팀원은 이름을 영어로 쓴다.** 팀원은 소문자와 하이픈만 쓰고 한글을 안 넣는다.

```
좋음   feat/order-cancel
나쁨   feat/주문-취소
```

## 커밋

**팀원은 한 줄로 쓴다. 팀원은 본문을 안 붙인다.**

```
[태그] 무엇이 달라지는지 한 문장
```

| 태그 | 언제 |
|---|---|
| `[Feat]` | 기능 추가 |
| `[Fix]` | 버그 수정 |
| `[Refactor]` | 구조만 변경 |
| `[Docs]` | 문서만 |
| `[Test]` | 테스트만 |
| `[Perf]` | 성능 개선 |
| `[Comment]` | 주석 추가나 수정 |
| `[Rename]` | 이름 변경, 파일 이동 |
| `[Remove]` | 파일 삭제 |
| `[Clean]` | 쓰지 않는 코드 정리 |
| `[Style]` | 포맷, 공백 |
| `[Chore]` | 빌드, 설정, 의존성 |

**팀원은 무엇을 했는지가 아니라 무엇이 달라지는지를 쓴다.**

```
나쁨   [Fix] OrderService.java 수정
좋음   [Fix] 취소된 주문이 다시 취소되지 않게 막는다
```

팀원은 평서형(`~한다`)으로 끝내고 마침표는 안 찍는다.
하나의 커밋은 되돌릴 수 있는 하나의 변경이다.

## PR

**대상은 `develop` 이다.** 팀원이 `main` 으로 열면 병합할 수 없다.

제목은 커밋과 같은 형식(`[태그] 한 문장`)이고, 본문은 PR 을 열면 템플릿이 채워진다.
팀원이 `.github/pull_request_template.md` 를 고치면 그 형식이 바뀐다.

병합 조건 셋이 전부 충족되어야 버튼이 살아난다.

| 조건 | |
|------|---|
| `G-BUILD` 통과 | 테스트, 커버리지 100%, 신규 Blocker 0건 |
| 다른 팀원 승인 1건 | 자기 PR 은 자기가 승인 못 한다 |
| 리뷰 대화 전부 해결 | `Resolve conversation` |

팀원이 승인 뒤에 코드를 더 올리면 **승인이 무효가 된다.** 팀원이 다시 받는다.

`G-PR`(LLM 판정)은 지적만 하고 막지 않는다. 팀원이 읽고 판단은 한다.

## 하지 않는 것

* 강제 push. `main` 과 `develop` 은 GitHub 이 막는다
* `--author` 로 남의 이름 쓰기. 팀원은 로컬 git 설정을 그대로 쓴다
* 무관한 변경을 한 커밋에 섞기

팀원은 병합된 브랜치를 GitHub 이 제안하는 `Delete branch` 로 지운다.

## 막혔을 때

| 증상 | 원인 |
|------|------|
| `GH006: Protected branch update failed` | `main` 이나 `develop` 에 직접 push |
| 병합 버튼이 회색 | 체크 실패, 승인 부족, 미해결 대화 중 하나 |
| 승인받았는데 다시 막힘 | 팀원이 승인 후 push 했다 |
| `G-PR` 이 skipped | `G-BUILD` 가 실패했으니 팀원이 그것부터 고친다 |

## 참고 자료

* 검증 도구 사용법: [verification/verification-guide.md](../verification/verification-guide.md)
* 코드 리뷰 점검 항목: [code-architecture/CODEREVIEW.md](../code-architecture/CODEREVIEW.md)
