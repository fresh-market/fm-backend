# 검증 현황

**이 문서는 `gen_status.py` 가 만든다. 손으로 고치지 않는다.**
레지스트리와 앵커 규칙에서 계산한 값이라, 항목이나 규칙이 바뀌면 다시 생성해야 한다.

생성 시각: 2026-09-26 17:45

| 저장소 | 커밋 | 항목 |
|---|---|---:|
| `common` | `18fb417` | 219 |
| `backend` | `8122cb87` | 276 |
| `infra` | `8586f4b` | 120 |
| | | **615** |

## 게이트가 지금 도는가

| 게이트 | 판정 주체 | 차단 | 상태 |
|---|---|---|---|
| G-LOCAL | LLM, 개발자 로컬 | 안 함 | 돈다 |
| G-BUILD | Gradle, SonarQube | **함** | 돈다 |
| G-PR | LLM, CI 자동 | 안 함 | 워크플로 있음. 엔진과 모델과 인증은 common 의 `llm-verify.yml` 이 정하고, 시크릿 등록 여부는 로컬에서 확인할 수 없다 |
| G-RELEASE | 배포 스크립트 | **함** | **스크립트 없음** |
| G-AUDIT | 주기 작업 | 안 함 | **없음** |
| 레지스트리 검사 | `gen_items.py --check` | **함** | 돈다 |

### 있는 것과 없는 것

```
  있음  backend 의 Java 코드  (528개)
  있음  backend/build.gradle
  있음  backend 앵커 규칙  (규칙 11개)
  있음  infra 앵커 규칙
  있음  G-PR 호출자 워크플로
  있음  G-PR 본체 워크플로
  있음  G-LOCAL 절차
  있음  레지스트리 검사 워크플로  (3개 저장소)
```

## 바꾼 파일별로 켜지는 항목

| 트리거 | 규칙 | 활성 | 1단계 | 2단계 | backend | common | infra |
|---|---|---:|---:|---:|---:|---:|---:|
| `**/internal/controller/*.java`, `**/internal/dto/*.java` | controller | 238 | 177 | 61 | 177 | 48 | 13 |
| `**/internal/service/*.java` | service | 198 | 85 | 113 | 85 | 87 | 26 |
| `**/internal/entity/*.java` | entity | 217 | 182 | 35 | 182 | 32 | 3 |
| `**/internal/repository/*.java` | repository | 135 | 100 | 35 | 100 | 35 | 0 |
| `**/internal/client/**/*.java` | external-client | 136 | 85 | 51 | 85 | 44 | 7 |
| `**/*Api.java`, `**/*ApiImpl.java` | api-contract | 196 | 177 | 19 | 177 | 15 | 4 |
| `src/main/resources/db/migration/*.sql` | migration | 103 | 51 | 52 | 51 | 48 | 4 |
| `src/main/resources/application*.yml`, `src/main/resources/logback*.xml` | app-config | 115 | 0 | 115 | 0 | 91 | 24 |
| `src/test/**/*Test.java`, `src/integrationTest/**/*.java` | test | 30 | 21 | 9 | 21 | 9 | 0 |
| `src/test/**/ArchitectureTest.java` | archunit | 35 | 35 | 0 | 35 | 0 | 0 |
| `build.gradle`, `settings.gradle` | build | 36 | 10 | 26 | 10 | 26 | 0 |
| (해당 없음) | 기본 집합 | 50 | 50 | 0 | 50 | 0 | 0 |

여러 파일을 바꾸면 합집합이다.

## 항목 분포

| 층위 | 건수 | 게이트 | 건수 |
|---|---:|---|---:|
| `[코드]` | 431 | `G-PR` | 477 |
| `[프로세스]` | 50 | `G-AUDIT` | 81 |
| `[인프라]` | 46 | `G-DESIGN` | 44 |
| `[설계]` | 44 | `G-RELEASE` | 13 |
| `[운영]` | 31 |  |  |
| `[실행전]` | 13 |  |  |

## PR 로 판정되지 않는 항목

615건 중 **493건**이 어떤 규칙엔가 걸린다. 나머지 122건은 아래와 같다.

| 게이트 | 건수 | 접두사 |
|---|---:|---|
| `G-AUDIT` | 74 | OPS 31, INC 24, CMP 4, FUN 3, REL 3, TRD 3, DI 2, OBS 2, FLX 1, SEC 1 |
| `G-PR` | 31 | INF 22, INC 9 |
| `G-RELEASE` | 13 | PRE 13 |
| `G-DESIGN` | 4 | INC 4 |

`G-AUDIT` 과 `G-RELEASE` 87건은 **빠진 것이 아니라 다른 게이트 소관이다.** 기록을 봐야 하거나 런타임 조회가 필요해 PR 단위로 판정할 수 없다.

**`G-PR` 과 `G-DESIGN` 35건은 다르다.** PR 에서 판정해야 하는데 어떤 앵커 규칙도 켜지 않는다. 실제로 열려 있는 구멍이다.

| 항목 | 층위 | 제목 |
|---|---|---|
| `INC-3-02` | 인프라 | 위 1~3번 조치를 배포 없이 즉시 실행할 수 있는가 |
| `INC-4-01` | 설계 | 인스턴스 하나가 죽어도 남은 인스턴스가 부하를 감당할 수 있는가 |
| `INC-4-02` | 인프라 | 분산 락에 만료 시간이 있어 급사한 인스턴스가 락을 영구 점유하지 않는가 |
| `INC-4-03` | 인프라 | 처리 중이던 메시지가 확인되지 않으면 재전달되는가 |
| `INC-4-05` | 코드 | split brain을 막는 장치(펜싱, 쿼럼)가 있는가 |
| `INC-4-06` | 인프라 | 쓰기 대상 전환이 배포 없이 가능한가 |
| `INC-5-01` | 인프라 | 이전 버전으로 되돌리는 데 걸리는 시간을 알고 있는가 |
| `INC-5-02` | 설계 | 스키마 변경이 코드 롤백을 막고 있지 않은가 |
| `INC-6-01` | 인프라 | 기동 후 트래픽을 단계적으로 유입시킬 수 있는가 |
| `INC-6-02` | 인프라 | 큐 소비 속도에 상한이 있는가 |
| `INC-6-03` | 설계 | 캐시가 빈 상태에서 원본이 감당 가능한 부하인가 |
| `INC-6-04` | 인프라 | 복구 직후 레이트 리밋을 평소보다 강하게 걸 수 있는가 |
| `INC-7-02` | 설계 | 중간 상태로 멈춘 건을 찾는 쿼리가 미리 준비되어 있는가 |
| `INF-12-01` | 코드 | `required_version` 과 AWS 프로바이더 버전이 고정되어 있는가 |
| `INF-12-02` | 코드 | 상태 백엔드가 S3 이고 `encrypt` 와 `use_lockfile` 이 켜져 있는가 |
| `INF-12-03` | 코드 | SSM `current-sha` 파라미터에 `ignore_changes = [value]` 가 걸려 있는가 |
| `INF-12-04` | 코드 | 앱 ASG 에 `ignore_changes = [desired_capacity]` 가 걸려 있는가 |
| `INF-12-05` | 코드 | 앱 ASG 가 `min_size` 0, `desired_capacity` 1, `max_size` 2, `health_check_type` `ELB` 인가 |
| `INF-12-06` | 코드 | ALB 에 `enable_deletion_protection` 과 `prevent_destroy` 가 둘 다 있는가 |
| `INF-12-07` | 코드 | 앱 시작 템플릿의 user-data 에 `batch` 프로필이 없는가 |
| `INF-12-08` | 코드 | 앱 시작 템플릿이 이미지 태그를 고정하지 않고 SSM 에서 읽는가 |
| `INF-12-09` | 코드 | 모니터링 인스턴스가 ASG 밖에 있는가 |
| `INF-12-10` | 코드 | NAT Gateway 리소스가 없는가 |
| `INF-12-11` | 코드 | 22번 포트를 여는 인바운드 규칙이 없는가 |
| `INF-12-12` | 코드 | 보안 그룹 인바운드가 CIDR 이 아니라 보안 그룹 참조인가 |
| `INF-12-13` | 코드 | 모든 CloudWatch 로그 그룹에 `retention_in_days` 가 있는가 |
| `INF-12-14` | 코드 | CloudWatch 알람이 6개 이하이고 전부 단일 지표인가 |
| `INF-12-15` | 코드 | RDS 에 `multi_az`, `backup_retention_period` 7, `deletion_protection`, `skip_final_snapshot = false` 가 있는가 |
| `INF-12-16` | 코드 | RDS 파라미터 그룹에 `max_connections` 를 지정하지 않았는가 |
| `INF-12-17` | 코드 | 캐시가 `aws_elasticache_cluster` 가 아니라 `aws_elasticache_replication_group` 인가 |
| `INF-12-18` | 코드 | ACM 검증용 DNS 레코드가 Terraform 관리 대상인가 |
| `INF-12-19` | 코드 | 대상 그룹이 트래픽 포트 8080 과 헬스체크 포트 8081 을 나눠 보는가 |
| `INF-12-20` | 코드 | 오토스케일링 정책을 만들었다면 지표가 `ALBRequestCountPerTarget` 인가 |
| `INF-12-21` | 코드 | 8081 을 여는 인바운드가 ALB 와 모니터링 보안 그룹뿐인가 |
| `INF-12-22` | 코드 | 80 에서 443 으로의 리다이렉트가 있는가 |

메우려면 이 항목들을 켜는 앵커 규칙을 만들거나, 층위를 고쳐 다른 게이트로 보낸다.

## 판정을 막고 있는 것

### 확정값 모순 0건

문서마다 다르게 적혀 있어 `CONFLICTING_BASELINE` 으로 유보된다. 한쪽을 골라 판정하면 LLM 이 팀의 결정을 대신 내리는 것이 된다.

| 주제 | 유보되는 항목 |
|---|---|

영향받는 항목은 중복 제외 **0건**이다. 결정하는 법은 `infra/docs/infra-review/pending-decisions.md` 에 있다.

### 의도된 이탈 1건

모순이 아니라 이 팀이 일반론에서 의도적으로 벗어난 것이다. 확정값 쪽으로 판정한다.

### 층위 미검증 396건

backend 276건, infra 120건 의 층위 태그가 기본값으로 채워져 있다. 실제와 다른 것이 섞여 있어 `levels` 필터가 정확하지 않다.

## 다시 생성하는 법

```bash
python3 common/.github/llm-verify/gen_status.py . \
        -o backend/docs/verification/verification-status.md
```

항목을 추가하거나 앵커 규칙을 고친 뒤에 돌린다.
