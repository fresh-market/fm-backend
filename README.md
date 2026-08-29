# fm-backend

신선식품 자사몰 백엔드. Java 21, Spring Boot 4.0.5, MySQL 8.4, Gradle.

```bash
git clone https://github.com/fresh-market/fm-backend.git backend
cd backend
./gradlew bootRun      # compose.yaml 의 MySQL 을 자동으로 띄운다
```

**처음 작업시 [docs/project-guideline.md](./docs/project-guideline.md) 부터 본다.**
작업 흐름, Merge를 차단하는 조건, 코드 작성시 볼 문서가 거기에 있다.

| | |
|---|---|
| [project-guideline.md](./docs/project-guideline.md) | 프로젝트 작업 가이드 라인 |
| [git-convention.md](./docs/git-convention.md) | 브랜치와 커밋 규칙 |
| [api/README.md](./docs/api/README.md) | API 명세 |
| [configuration.md](./docs/configuration.md) | 설정 파일과 로컬 DB |
| [resource-budget.md](./docs/resource-budget.md) | 인스턴스별 커넥션과 스레드 예산 |
| [code-architecture/domain-map.md](./docs/code-architecture/domain-map.md) | 13개 도메인과 층 |
| [verification/verification-guide.md](./docs/verification/verification-guide.md) | 검증 도구 사용법 |
| [WIKI](https://github.com/fresh-market/fm-backend/wiki) | 팀 문서, 문제 해결 공유 |

작업은 [프로젝트 보드](https://github.com/orgs/fresh-market/projects/6) 의 이슈에서 시작한다.
