# 설정 파일

로컬 DB 접속에 관여하는 파일은 다섯이다. **평소에는 아무것도 안 고쳐도 된다.**
포트가 겹칠 때만 `.env` 한 줄을 적는다.

| 파일 | 누가 읽나 | 역할 | 추적 |
|---|---|---|---|
| `compose.yaml` | Docker Compose | MySQL 컨테이너를 **만든다** | O |
| `application.yml` | Spring Boot | 환경과 무관한 공통 설정 | O |
| `../src/main/resources/application-local.yml` | Spring Boot | 앱이 쓸 로컬 값. 비밀값이 여기 있다 | **X** |
| `application-local.yml.example` | 사람 | 거기 무엇을 적는지 보여준다 | O |
| `.env` | Docker Compose | `compose.yaml` 의 값을 **덮는다** | **X** |
| `.env.example` | 사람 | `.env` 에 무엇을 적을 수 있는지 보여준다 | O |

`.env` 만 추적하지 않는다. **그래서 각자 다른 포트를 써도 남의 설정을 건드리지 않는다.**

## 프로필

환경마다 갈리는 값은 `application.yml` 이 아니라 프로필 파일에 둔다.
`application.yml` 은 언제나 먼저 읽히고 프로필 파일이 그 위에 덮는다.

| 파일 | 언제 읽히나 | 추적 |
|---|---|---|
| `application.yml` | 항상 | O |
| `../src/main/resources/application-local.yml` | 로컬. 기본값이라 따로 켤 것이 없다 | **X** |
| `application-prod.yml` | `SPRING_PROFILES_ACTIVE=prod` | O |
| `src/integrationTest/resources/application-integrationTest.yml` | `./gradlew integrationTest` | O |

프로필을 아무도 지정하지 않으면 `local` 로 본다. `application.yml` 이 그렇게 잡는다.

```yaml
spring:
  profiles:
    default: local
```

```
No active profile set, falling back to 1 default profile: "local"
```

## 동작 플로우

```
./gradlew bootRun
      |
      1. spring-boot-docker-compose 가 compose.yaml 을 찾는다
      |     (developmentOnly 의존성이라 bootJar 에는 없다)
      |
      2. Compose 가 .env 를 읽어 ${...} 를 치환하고 컨테이너를 띄운다
      |     .env 가 없으면 compose.yaml 의 기본값을 쓴다
      |
      3. Boot 가 뜬 컨테이너에서 접속 정보를 직접 읽어 주입한다
      |     이 값이 application.yml 의 spring.datasource 를 덮는다
      |
      4. Flyway 가 db/migration 의 V1 을 적용한다
      |
      5. Hibernate 가 ddl-auto: validate 로 스키마를 대조한다
      |
    기동 완료
```

3번이 핵심이다. **`bootRun` 으로 띄우는 동안 `application.yml` 의 url, username, password 는 사실상 쓰이지 않는다.**
컨테이너에서 읽어낸 값이 이기기 때문이다. 그 셋이 쓰이는 것은 compose 없이 붙을 때다.

| 실행 방법 | 접속 정보의 출처 |
|---|---|
| `./gradlew bootRun` | compose 가 띄운 컨테이너 |
| 통합 테스트 | Testcontainers 가 따로 띄운 컨테이너 |
| `java -jar` | 켠 프로필의 파일 또는 환경변수 |

## 값을 덮는 순서

```
명령행 인자  >  OS 환경변수  >  jar 밖 application.yml  >  클래스패스 application.yml
```

`application.yml` 은 클래스패스에 있어 가장 약하다. 그래서 파일을 고치지 않고도 덮을 수 있다.

```bash
DB_URL=jdbc:mysql://localhost:3307/freshmarket ./gradlew bootRun
```

## 문법이 서로 다르다

같은 `${}` 로 보이지만 읽는 주체가 달라 기본값 표기가 다르다. **바꿔 쓰면 조용히 틀리거나 오류가 난다.**

```yaml
# application.yml   Spring     콜론 하나가 기본값 구분자
url: "${DB_URL:jdbc:mysql://localhost:3306/freshmarket}"

# compose.yaml      Compose    콜론+하이픈이 기본값 연산자
ports: ["${MYSQL_PORT:-3306}:3306"]
```

Compose 에서 `-` 를 빼면 `invalid interpolation format` 으로 죽는다.
Spring 에서 `:-` 를 쓰면 기본값이 `-3306` 이 된다.

`:-` 와 `-` 의 차이는 빈 문자열을 어떻게 보느냐다.

| `.env` 의 값 | `${V:-기본값}` | `${V-기본값}` |
|---|---|---|
| 줄 자체가 없음 | 기본값 | 기본값 |
| `V=` (비어 있음) | **기본값** | **빈 문자열** |
| `V=값` | 값 | 값 |

`compose.yaml` 은 전부 `:-` 를 쓴다. **`.env` 에 `MYSQL_PORT=` 처럼 비워 두는 실수를 기본값으로 되돌리기 위해서다.**

## 언제 무엇을 고치나

| 상황 | 할 일                                       |
|---|-------------------------------------------|
| 아무 문제 없음 | 아무것도 안 한다                                 |
| 3306 이 이미 쓰인다 | `.env` 에 `MYSQL_PORT=3307`                |
| 컨테이너 이름이 겹친다 | `.env` 에 `MYSQL_CONTAINER_NAME=...`       |
| compose 없이 다른 DB 에 붙는다 | 셸에 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` |

```bash
cp .env.example .env     # 필요한 줄만 남기고 고친다
```

**`.env` 는 Compose 만 읽는다. Spring 은 읽지 않는다.**
`DB_URL` 같은 값을 `.env` 에 적어도 앱에 전달되지 않는다. 셸 환경변수로 줘야 한다.

## 손으로 다룰 때

`bootRun` 이 알아서 띄우므로 평소에는 쓸 일이 없다. DB 만 켜 두거나 안을 들여다볼 때 쓴다.

```bash
docker compose up -d          # DB 만 띄운다
docker compose ps             # 떠 있나. healthy 가 될 때까지 기다린다
docker compose logs -f mysql  # 안 뜰 때 이유를 본다
docker compose down           # 끈다. 데이터는 남는다
docker compose down -v        # 끄고 데이터까지 지운다
```

SQL 로 들어갈 때는 컨테이너 안의 클라이언트를 쓴다. 호스트에 mysql 을 깔지 않아도 된다.

```bash
docker compose exec mysql mysql -h127.0.0.1 -ufreshmarket -pfreshmarket freshmarket
```

**`-h127.0.0.1` 을 뺐다가는 소켓으로 붙으려다 실패한다.**

## 안 될 때

| 증상 | 원인과 조치 |
|---|---|
| `Bind for 0.0.0.0:3306 failed: port is already allocated` | 포트가 이미 쓰인다. `.env` 에 `MYSQL_PORT` 를 다른 값으로 |
| 무엇이 그 포트를 쥐었는지 모르겠다 | `lsof -nP -iTCP:3306 -sTCP:LISTEN` |
| `Migration checksum mismatch` | 적용된 마이그레이션 파일이 바뀌었다. `docker compose down -v` 로 비운다 |
| 표가 하나도 안 생긴다 | Flyway 자동설정이 안 붙었다. 아래 절을 본다 |
| `application.yml` 을 고쳤는데 안 바뀐다 | `bootRun` 은 컨테이너에서 읽은 값이 이긴다. 위 플로우 3번 |
| `.env` 에 `DB_URL` 을 적었는데 안 먹는다 | `.env` 는 Compose 만 읽는다. 셸 환경변수로 준다 |
| 컨테이너 이름이 겹친다 | `.env` 에 `MYSQL_CONTAINER_NAME` |

## 스키마는 Flyway 가 소유한다

```yaml
spring.jpa.hibernate.ddl-auto: validate
spring.flyway.enabled: true
```

**Hibernate 는 스키마를 만들지도 고치지도 않는다.** 엔티티와 어긋나면 기동을 막는 역할만 한다.
표를 바꾸려면 `src/main/resources/db/migration/` 에 새 마이그레이션을 더한다. 적용된 파일은 고치지 않는다.

`V1__init_schema.sql` 이 바뀌면 체크섬이 달라져 기존 로컬 DB 와 충돌한다. 그때는 비운다.

```bash
docker compose down -v
```

Flyway 자동설정은 `spring-boot-flyway` 의존성이 있어야 붙는다.
Boot 4 는 자동설정을 기술별 모듈로 쪼갰고, **`flyway-core` 만 있으면 마이그레이션이 조용히 건너뛰어진다.**

## 앱이 쓰는 비밀값

위 표는 전부 DB 접속에 관한 것이다. 카카오 키나 JWT 서명 키처럼 **앱이 직접 쓰는 값은 경로가 다르다.**

`application.yml` 은 그런 키를 기본값 없이 `"${VAR}"` 로 둔다. 전부 비밀값이라 기본값을 주면
값을 빼먹은 채로 뜨는 것을 못 막기 때문이다. **그래서 값을 주지 않으면 앱이 아예 뜨지 않는다.**

| 환경 | 어디서 주나 |
|---|---|
| 로컬 | `src/main/resources/application-local.yml` |
| 운영 | 환경변수. 값은 SSM Parameter Store 에 두고 배포 스크립트가 읽어 넣는다 |

**운영 값이 든 파일은 저장소에 두지 않는다.** `application-prod.yml` 에는 이름만 적혀 있다.

로컬은 같은 폴더의 템플릿을 복사해서 채운다.

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

**따로 켤 것은 없다.** `application.yml` 이 기본 프로필을 `local` 로 둔다.
파일이 없으면 조용히 넘어가고, 그 값을 쓰는 빈이 만들어질 때 기동이 막힌다.

`.env` 와 헷갈리지 않는다. `.env` 는 Compose 만 읽고 컨테이너를 어떻게 띄울지에만 관여한다.
카카오 키를 `.env` 에 적어도 앱에는 전달되지 않는다.

**`src/main/resources` 는 jar 에 담기는 자리다.** `../src/main/resources/application-local.yml` 은 저장소에는 안 올라가지만,
로컬에서 `bootJar` 를 만들면 그 값이 jar 안에 들어간다. 그렇게 만든 jar 는 남에게 주지 않는다.

## 올리면 안 되는 것

`.gitignore` 가 아래를 막는다.

```
.env  .env.*                                              (단 .env.example 은 통과)
application-local.yml  .yaml  .properties                 (단 .example 은 통과)
*.pem  *.key  *.p12  *.jks  *.keystore
```

**실제 값이 든 파일은 올리지 않고, 무엇을 적을 수 있는지 보여주는 `.example` 만 올린다.**
`.env.example` 과 `application-local.yml.example` 둘이다.
