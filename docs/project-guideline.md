# 프로젝트 가이드

## 시작하기

```bash
git clone https://github.com/fresh-market/fm-backend.git backend
cd backend
./gradlew bootRun      # compose.yaml 의 MySQL 을 자동으로 띄운다
```

Java 21, Spring Boot 4.0.5, MySQL 8.4, Gradle.
버전은 [tech-stack.md](./tech-stack.md) 에 있고 **Spring Boot BOM 이 관리하는 것은 버전을 박지 않는다.**

## 작업 흐름

작업은 이슈에서 시작한다. [프로젝트 보드](https://github.com/orgs/fresh-market/projects/6) 에서 무엇을 잡을지 고른다.

```
1. 이슈를 만든다        템플릿에 도메인과 작업 체크리스트가 뜬다
                       보드에 Todo 로 자동 등록된다
2. 담당자를 자신으로     Status 를 In Progress 로 옮긴다
3. 아래대로 작업한다
4. PR 본문에 Closes #12
5. 병합되면 이슈가 닫히고 카드가 Done 으로 간다
```

**이슈 없이 시작하지 않는다.** 보드에 없는 작업은 남들이 모르고, 같은 것을 두 사람이 잡는다.

```bash
git switch develop && git pull
git switch -c feat/order-cancel

# 작업하고 커밋 (한 줄, [태그] 형식)
git commit -m "[Feat] 주문 취소 시 재고를 되돌린다"

./gradlew check        # 이것이 통과해야 병합된다
/v-commit              # 점검 항목 판정이며 PR 를 막지는 않는다. Claude CLI 모드에서 실행시킨다
                       # --full 옵션은 ISO 품질 속성과 인프라 기준까지 (토큰과 시간 비용이 크므로 권장하지 않음)

git push -u origin feat/order-cancel
gh pr create --base develop     # 본문에 Closes #12
```

PR 을 열면 카드를 **`In Review`** 로 옮긴다. 누가 리뷰를 기다리는지 보이게 하는 자리다.

`/v-commit` 은 **Claude Code 를 띄운 뒤 그 안에서 치는 슬래시 명령**이다. 터미널에서는 동작하지 않는다.
다른 CLI 를 쓰면 터미널에서 `./verify.sh --agent <명령>` 으로 넘긴다.

**`./verify.sh` 만 치면 지시문만 내고 판정은 하지 않는다.**

자세한 규칙은 [git-convention.md](./git-convention.md).

## Merge를 차단하는 것

| | |
|---|---|
| `*.internal.service.*` 메서드 커버리지 **100%** | 여유가 없다. 서비스 메서드를 만들면 테스트도 만든다 |
| SonarQube 신규 **Blocker 0건** | |
| 다른 팀원 승인 **1건** | 자기 PR 은 자기가 승인 못 한다 |

**`main` 에는 직접 못 올린다.** 팀원은 `develop` 으로만 PR 을 연다.

LLM 판정(G-PR)은 지적만 하고 막지 않는다. 읽고 판단은 한다.

## 코드 작성시 참고 자료

`docs/code-architecture/` 에 판정 기준이 있다. **리뷰에서 지적되는 근거가 전부 여기 있다.**

| 무엇을 만들 때 | 볼 문서 |
|---|---|
| 어느 도메인에 넣을지 | [`domain-map.md`](./code-architecture/domain-map.md) |
| 엔티티 | [`entity-creation-guideline.md`](./code-architecture/entity-creation-guideline.md), [`base-entity-guideline.md`](./code-architecture/base-entity-guideline.md) |
| 레포지토리, 쿼리 | [`jpa-rdb-guideline.md`](./code-architecture/jpa-rdb-guideline.md) |
| 컨트롤러, DTO | [`api-design-guideline.md`](./code-architecture/api-design-guideline.md) |
| 패키지 배치 | [`domain-package-boundary-guideline.md`](./code-architecture/domain-package-boundary-guideline.md) |
| 테스트 | [`unit-testing-guideline.md`](./code-architecture/unit-testing-guideline.md) |
| 테스트를 어디에 둘지 | 단위 `src/test/java`, 통합 `src/integrationTest/java` |
| 식별자 | [`identifier-strategy-guideline.md`](./code-architecture/identifier-strategy-guideline.md) |

**각 문서에는 짝이 되는 `-rationale.md` 가 있다.** 규칙은 guideline, 왜 그런지는 rationale 이다.

[`domain-map.md`](./code-architecture/domain-map.md) 는 13개 도메인과 층을 정해 둔 것이다. **호출은 아래로만 하고 같은 층끼리는 부르지 않는다.**
`ArchitectureTest` 가 빌드에서 강제하므로 어기면 `./gradlew check` 가 실패한다.

## 문서 작성

| 어디에 | 무엇을 |
|---|---|
| `docs/wiki/` | 팀 문서, 문제 해결 공유. GitHub 위키로 자동 반영된다 |
| `docs/code-architecture/` | 코드와 함께 봐야 하는 기준. 직접 쓰지 않는다 |
| `docs/llm-review/` | 검증 기록. 직접 쓰지 않는다 |

## 참고 자료

* API 명세: [api/README.md](./api/README.md)
* 설정 파일과 로컬 DB: [configuration.md](./configuration.md)
* 검증 도구 사용법: [verification/verification-guide.md](./verification/verification-guide.md)
* 코드 리뷰 점검 항목: [code-architecture/CODEREVIEW.md](./code-architecture/CODEREVIEW.md)
* 빌드 게이트 기준: [code-architecture/build-gate-guideline.md](./code-architecture/build-gate-guideline.md)
