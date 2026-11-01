# `rebuild-v2` 회차: 메모리 한도를 넣고 옛 재건과 새 재건을 나란히 잼

같은 날 같은 인프라에서 두 빌드를 돌렸다. **컨테이너 메모리 한도가 들어간 첫 회차다.**

```
X-1 ~ X-3   938fd9da   main.  재건이 발급을 멈추는 옛 설계
Y-1 ~ Y-3   b632a5bd   develop.  #242 가 들어간 새 설계
```

한도는 `fm-infra` 가 건다. **앱 빌드와 무관하게 두 판에 똑같이 적용됐다.**

```
컨테이너 1,200 MiB   MaxRAMPercentage=60 -> 힙 720 MiB,  MaxDirectMemorySize=128m
관측 셋              cadvisor 128,  alloy 128,  node-exporter 48 MiB
호스트               t3.small,  free -m 으로 1,905 MiB
```

회차 전에 세 대 모두에서 `docker inspect` 로 `HostConfig.Memory` 와 JVM 플래그와 덤프
디렉터리 소유를 확인했다. **한도가 안 걸린 채로 도는 판을 막는 관문을 이번에 처음 넣었다.**

---

## 1. 결과

| 회차 | 빌드 | 장애 | 발급 | 미판매 | 결번 | 혼잡률 | 실패율 | http p99 |
|---|---|---|---:|---:|---:|---:|---:|---:|
| X-1 | main | 서버 1대 | 10,000 | 0 | 0 | 1.36% | 26.14% | - |
| X-2 | main | 캐시 페일오버 | 9,837 | 163 | 0 | 84.83% | 0.00% | - |
| X-3 | main | 복합 | 9,572 | 428 | 0 | 55.07% | 30.49% | - |
| Y-1 | #242 | 서버 1대 | **무효** | - | - | - | - | - |
| Y-2 | #242 | 캐시 페일오버 | 9,596 | 404 | **404** | 71.57% | 17.06% | **60s** |
| Y-3 | #242 | 복합 | 9,120 | 880 | 3 | 56.57% | 30.61% | - |

**초과 발급은 여섯 회차 전부 0 이다.** `members == issued_rows` 가 모두 성립했고, 재건이
한 번도 완주 못 한 Y-3 에서도 그랬다. **정합성은 DB 제약이 지키며 재건과 무관하다.**

### Y-1 을 버리는 까닭

```
19:19:03   k6 를 띄운다
19:27:39   +15초에 주입한다      8분 36초 늦었다.  k6 는 이미 끝나 있었다
발급 0건
k6 요약    타임스탬프가 10:03:46Z 로 X-3 것이 복사됐다
```

### Y-3 은 재건을 잰 것이 아님

```
COUPON_SEQ_REBUILD_RETRY  attempt=1  QueryTimeoutException
COUPON_SEQ_REBUILD_RETRY  attempt=2  QueryTimeoutException
COUPON_SEQ_REBUILD_FAILED attempts=3
```

`b632a5bd` 의 재건 전용 Redis 타임아웃이 **1초**다. 3초로 올린 `cf6a95a` 는
`fix/rebuild-redis-timeout` 브랜치에만 있고 `develop` 에 안 들어갔다.
**Y-3 의 9,120 과 p99 1.69초는 재건이 없는 시스템의 성적이다.**

---

## 2. OOM 이 처음으로 증거를 남김

Y-2 에서 쿠폰 인스턴스 하나가 죽었다. **캐시 페일오버는 인스턴스를 안 죽이므로 이것은 OOM 이다.**

```
java.lang.OutOfMemoryError: Java heap space
Heap dump file created [811831046 bytes in 17.651 secs]
Terminating due to java.lang.OutOfMemoryError: Java heap space
```

```
커널 oom-kill   0건        전에는 커널이 호스트에서 자바를 골랐다
컨테이너만 죽고 docker 가 되살렸다
working set     792 / 920 / 867 MiB,  한도 1,200 MiB
```

### 힙을 채운 것

덤프를 인스턴스 위에서 파싱했다. 811MB 를 내려받지 않았다.

```
총 702.4 MB, 객체 4,890,472개

     321.5 MB    518,156   byte[]
     146.4 MB     18,994   char[]
      16.5 MB    262,085   org.apache.tomcat.util.buf.ByteChunk
      13.7 MB    248,153   org.apache.tomcat.util.buf.CharChunk
      13.5 MB    243,509   org.apache.tomcat.util.buf.MessageBytes
       2.0 MB      7,450   org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper
       1.5 MB      7,450   java.lang.VirtualThread
       1.5 MB      4,643   org.apache.coyote.Request
```

**`NioSocketWrapper` 와 `VirtualThread` 가 둘 다 7,450 이다.** 동시에 떠 있던 연결이 7,450개다.

```
톰캣 버퍼 합계 약 511 MB  /  연결 7,450  =  연결 하나당 약 68 KB
server.tomcat.max-connections 기본값 8192,  7,450 은 그 91%
8192 x 68KB = 약 557 MB.  힙 720 MiB 안에서 나머지와 합치면 넘는다
```

**상위 30개에 쿠폰 도메인 객체가 하나도 없다.** `IssueTicket` 도 `CompletableFuture` 도
큐도 없다. 힙을 채운 것은 발급 기계가 아니라 동시 연결이다.

### 왜 이 빌드에서만 났나

`main` 의 발급 Lua 는 재건 락이 있으면 `-2` 를 내고 요청을 즉시 503 으로 끊는다.
**#242 가 그 세 줄을 걷었고, 그것이 의도치 않은 부하 차단기였다.**

리틀의 법칙으로 동시성이 나온다.

```
X-2 main    939/s x 0.22초  =     207
Y-2 #242    628/s x 8.26초  =   5,187      덤프의 7,450 과 같은 자릿수
```

**#242 의 결함은 메모리를 더 쓰는 것이 아니라 차단기를 걷으면서 대체물을 안 둔 것이다.**

---

## 3. 판정 지표가 참사를 가림

이것이 이 회차의 가장 큰 수확이다.

```
Y-2 의 같은 구간

  coupon_settled_duration   p(99) 748ms          통과로 보였다
  http_req_duration         p(90) 59.99s  p(95) 1m0s  max 1m0s
```

`settled` 는 **답을 받은 요청만** 담는다. 장애 회차에서 답을 못 받은 요청이 바로 가장 느린
요청들이라 **모집단이 좋은 쪽으로 체계적으로 치우친다.**

아홉 회차를 다시 읽으면 규칙이 하나다.

| 회차 | settled p95 | http p95 | 실패율 |
|---|---:|---:|---:|
| V-3 | 651.22ms | 928.23ms | 0.00% |
| X-2 | 398.07ms | 372.22ms | 0.00% |
| X-1 | 68.82ms | 68.86ms | 26.14% |
| X-3 | 109.29ms | 231.09ms | 30.49% |
| Y-3 | 783.92ms | 437.54ms | 30.61% |
| V-4 | 113.43ms | 962.43ms | 28.31% |
| W-1 | 535.16ms | 10s | 54.22% |
| Y-2 | 223.44ms | 1m0s | 17.06% |

**실패율이 0% 인 회차에서만 둘이 일치한다.**

같은 이유로 **p99 를 회차끼리 가로로 못 비교한다.** X-3 이 복합인데 p99 285ms 로 가장
빨라 보이는 것이 그 예다. 그 회차는 실패율이 30.49% 이고 `settled` 의 `max` 가 391.88ms 다.
**400ms 를 넘긴 요청이 한 건도 판정 안 됐다는 뜻이다.**

같은 왜곡이 2026-10-01 F-2 에도 있었고 그때 문서가 "OOM 으로 왜곡" 이라 적었다.

### 고친 것

```
k6 임계   coupon_settled_duration 을 빼고 http_req_duration p(99) < 1000 을 건다
          settled 는 출력에 남긴다.  둘의 괴리가 모집단이 깎였는지를 말해 준다
```

**그라파나에는 `settled` 패널이 없다.** `http_server_requests_seconds` 와
`k6_http_req_duration_p99` 뿐이다. 지금까지 회차 판정과 대시보드가 서로 다른 숫자를
보고 있었고, 이 변경으로 둘이 처음 같아진다.

---

## 4. 이 회차가 정한 것

```
메모리     한도와 덤프와 다이렉트 상한은 제 일을 했다.  커널 OOM 이 사라졌고 까닭이 남았다
차단기     인플라이트 상한이 필요하다.  값은 덤프가 준 68 KB 로 계산한다
타임아웃   cf6a95a 를 develop 에 병합해야 한다
지표       settled 단독 판정을 버린다
```

**아직 모르는 것도 적어 둔다.**

```
free 되돌리기   Y-2 에서 candidates=352 added=0 이었다.  두 갈래를 로그로 못 가른다
A 단계          COUPON_SEQ_RAISED 를 수집 패턴이 안 걷어 돌았는지 기록이 없다
플러시 정지     #242 가 얻으려던 값인데 이번에도 안 쟀다
```
