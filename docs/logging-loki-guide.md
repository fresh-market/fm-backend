# 로그 모니터링 — Loki 도입 가이드

이 문서는 아직 실제로 적용된 인프라가 아니라, "지금 있는 로깅을 Loki로 어떻게 흘려보내고 어떻게
분석할지"에 대한 전략 초안이다. `docker-compose.yaml`이나 `build.gradle`은 아직 안 건드렸다 —
실제로 도입하기로 결정하면 이 문서 기준으로 반영한다.

## 0. 지금 상태 — 이미 Loki 붙이기 좋게 돼 있다

- `logback-spring.xml`의 `prod` 프로필이 `logstash-logback-encoder`로 JSON을 stdout에 찍는다.
- MDC(`traceId`, `method`, `uri`, `clientIp`)가 자동으로 JSON 필드에 실린다(`MdcLoggingFilter`).
- 코드 전반에 `event=EVENT_NAME key=value` 구조화 로그 컨벤션이 이미 있다
  (`event=KAKAO_LOGIN_FAILED reason=...`, `event=EXTERNAL_API_CALL method= uri= status= durationMs=`,
  `event=REFRESH_TOKEN_REUSE_SUSPECTED role= id= jti=` 등).
- `PiiMasker`가 이메일/전화번호/카카오 회원번호 등을 로그에 남기기 전에 마스킹한다.

즉 수집 전략만 정하면 되고, 로그 자체의 포맷을 새로 설계할 필요는 없다.

## 1. 수집 방식 — 두 옵션

### 옵션 A: Docker Loki 로깅 드라이버
컨테이너의 `logging.driver`를 `loki`로 바꾸면 도커 데몬이 stdout을 바로 Loki로 밀어넣는다.
- 장점: 별도 수집 에이전트가 없어 로컬 개발에 제일 간단하다.
- 단점: 호스트마다 `loki-docker-driver` 플러그인 설치가 필요하고, 나중에 배포 환경이
  쿠버네티스 등으로 바뀌면 이 방식 자체를 못 쓴다.

### 옵션 B: Promtail(또는 최신 Grafana Alloy)이 로그를 스크레이핑
컨테이너는 지금처럼 `json-file` 드라이버로 stdout에 로그를 찍고, Promtail이
`/var/lib/docker/containers/*/*.log`를 tail해서 Loki로 보낸다.
- 장점: 로컬/운영 어디서든 같은 방식이다(운영이 쿠버네티스로 가도 Promtail이 DaemonSet으로
  그대로 옮겨간다).
- 단점: compose에 컨테이너가 하나 더 늘어난다.

**추천: 옵션 B.** 지금은 로컬 docker-compose 단계지만 실제 배포 환경은 따로 있을 가능성이 높다 —
A는 로컬에서 편한 대신 배포 환경이 바뀌면 통째로 버려야 한다.

## 2. 인프라 설정

`compose.yaml`에 추가할 서비스 초안:

```yaml
loki:
  image: grafana/loki:3.x
  container_name: freshmarket-loki
  ports:
    - "3100:3100"
  command: -config.file=/etc/loki/local-config.yaml

promtail:
  image: grafana/promtail:3.x
  container_name: freshmarket-promtail
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock:ro
    - /var/lib/docker/containers:/var/lib/docker/containers:ro
    - ./promtail-config.yaml:/etc/promtail/config.yaml
  command: -config.file=/etc/promtail/config.yaml
  depends_on:
    - loki

grafana:
  image: grafana/grafana:11.x
  container_name: freshmarket-grafana
  ports:
    - "3000:3000"
  depends_on:
    - loki
```

`promtail-config.yaml`은 `docker_sd_configs`로 컨테이너를 찾아 Loki로 보내는 표준 설정이면
되고, Grafana에는 Loki를 데이터소스로 등록만 하면 된다.

### 의존성

**애플리케이션(`build.gradle`) 쪽엔 새 의존성이 필요 없다.** 이미 JSON으로 stdout에 찍고 있어서
Promtail이 그 출력을 그대로 걷어가면 끝이다.

`loki4j-logback-appender`처럼 앱이 Loki로 직접 push하는 라이브러리도 있지만 채택하지 않는다 —
앱이 Loki 엔드포인트를 알아야 하고, Loki가 잠깐이라도 응답이 늦으면 로깅이 앱 스레드에 영향을
줄 수 있다. 지금처럼 "stdout에 찍기 + 외부 수집기가 걷어가기"로 분리하는 쪽이 안전하다.

## 3. 라벨 설계

Loki는 라벨마다 별도 스트림을 만든다 — **`traceId`/`memberId`/`uri`처럼 값이 무한히 다양한
필드를 라벨로 넣으면 안 된다**(카디널리티 폭발, Loki가 느려지거나 죽는 제일 흔한 원인).

- 라벨로 둘 것: `service=freshmarket`, `env=local|prod`, `container` 정도.
- 라벨로 두면 안 되는 것: `traceId`, `event`, `reason`, `memberId`, `uri` 등 — 이런 건 JSON
  로그 라인 안의 **필드**로만 남기고, 쿼리할 때 `| json`으로 파싱해서 필터링한다. 지금
  `includeMdcKeyName`으로 `traceId`를 필드로 넣고 있는 방식이 이미 맞는 방향이다.

## 4. LogQL 예시 — 지금 있는 `event=` 컨벤션 기준

특정 요청 하나의 전체 로그 추적:
```
{service="freshmarket"} | json | traceId="abc123"
```

카카오 로그인 실패 사유별 집계:
```
sum by (reason) (count_over_time({service="freshmarket"} | json | event="KAKAO_LOGIN_FAILED" [5m]))
```

리프레시 토큰 재사용(탈취 의심) 급증 탐지:
```
sum(count_over_time({service="freshmarket"} | json | event="REFRESH_TOKEN_REUSE_SUSPECTED" [5m]))
```

카카오 API 응답시간 p95 추이(`ExternalApiLoggingExchangeFilter`의 `durationMs` 필드 활용):
```
quantile_over_time(0.95, {service="freshmarket"} | json | event="EXTERNAL_API_CALL" | unwrap durationMs [5m])
```

Redis 장애 시 DB 폴백 발생 빈도(`MemberTokenService`의 `REDIS_SAVE_FAILED`/`REDIS_CAS_FAILED`):
```
sum(count_over_time({service="freshmarket"} | json | event=~"REDIS_(SAVE|CAS)_FAILED" [5m]))
```

에러 레벨 전체 추이(기본 대시보드용):
```
sum by (level) (count_over_time({service="freshmarket"} | json [5m]))
```

## 5. 알림

Grafana Alerting(또는 Loki Ruler)로 위 쿼리에 임계값을 걸면 된다. 초기 알림 후보 세 개:

- `REFRESH_TOKEN_REUSE_SUSPECTED`가 짧은 시간에 급증 → 토큰 탈취 의심
- `EXTERNAL_API_CALL_FAILED`(카카오) 비율 급증 → 카카오 장애 의심
- `level="ERROR"` rate 급증 → 일반 장애 알림

## 6. 주의할 점

- Loki는 필드 단위 마스킹/암호화 기능이 없다 — 로그에 뭐가 찍히든 그대로 저장되고 검색된다.
  마스킹은 반드시 지금처럼 애플리케이션이 로그를 쓰기 **전에** `PiiMasker`로 끝내야 한다. Loki를
  붙인다고 이 규율이 느슨해지면 안 되고, 오히려 검색이 쉬워지는 만큼 마스킹 안 된 필드가 하나라도
  새로 생기면 노출 범위가 넓어진다.
- Loki 자체에 retention(`compactor.retention_period`, 기본 무제한)을 꼭 설정해야 한다 — 안 그러면
  디스크가 계속 찬다.

## 7. 남은 결정 사항

- 옵션 A/B 중 최종 선택 (이 문서는 B를 전제로 초안을 잡음)
- Loki/Grafana 컨테이너를 로컬 compose에 상시 포함할지, 아니면 필요할 때만 별도 compose
  프로필로 띄울지
- retention 기간을 얼마로 할지(로컬/운영 각각)
