package com.freshmarket.coupon.internal.warmup;

import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 기동 직후 발급 경로에 트래픽을 흘려 JIT 을 데운다.
 *
 * <p><b>이 클래스가 {@code ApplicationRunner} 인 것이 설계의 요점이다.</b> 러너는
 * {@code ApplicationReadyEvent} 앞에 돌고, 스프링 부트는 그 이벤트에서 readiness 를
 * {@code ACCEPTING_TRAFFIC} 으로 올린다. 그래서 여기서 데우면 readiness 를 직접 건드리지
 * 않아도 자연히 늦춰진다.
 *
 * <p>그러면 {@code coupon-event.sh open} 의 "healthy 대기" 가 곧 "warm 대기" 가 되어
 * 스크립트도 운영 절차도 바뀌지 않는다.
 *
 * <p>왜 필요한가. 차가운 JVM 은 요청을 처리하면서 동시에 자기를 컴파일한다. 90초짜리 이벤트에서
 * 49.7초어치를 컴파일했고 그만큼이 요청 처리에서 빠졌다. 실측 차이가 이만큼이다
 * (2026-08-31, VU 20,000 / 재고 10,000 / t3.small 3대).
 *
 * <pre>
 * 워밍업 없음        p99 4.69초
 * 워밍업 3,000건     p99 0.288 ~ 0.939초 (표본 5)
 * </pre>
 *
 * <p>자세한 측정은 {@code fm-infra} 의
 * {@code docs/verification/선착순쿠폰_워밍업_설계와측정.md} 에 있다.
 */
@Slf4j
@Component
@Profile("coupon")
@RequiredArgsConstructor
public class CouponWarmupRunner implements ApplicationRunner {

    /*
     * 워밍업이 쓸 회원 식별자다. 실재하지 않아도 된다.
     *
     * 러너가 카운터를 소진 상태로 세워 두어 요청이 순번 확보에서 끝나고 member_coupon 에
     * 아무것도 안 쓴다. 그래서 fk_mc_member 에 걸릴 일이 없다. 토큰의 주체로만 쓰인다.
     */
    private static final long WARMUP_MEMBER_ID = -1L;

    private static final String ROLE = "ROLE_MEMBER";

    /*
     * 워밍업 쿠폰의 카운터를 이 값으로 세워 늘 소진 상태로 둔다.
     *
     * 카운터가 없으면 순번 확보 스크립트가 첫 줄에서 -2 를 돌려주고 끝난다. 그러면 데우려던
     * 경로(Lua, 회로, 큐)를 하나도 안 지나고, 그 -2 가 재건까지 깨운다. 재건기는 켜져 있고
     * 수량이 있는 쿠폰의 카운터가 없으면 손실로 보기 때문이다.
     *
     * 총량보다 크기만 하면 되므로 int 상한을 쓴다. V33 의 total_quantity 값에 안 묶인다.
     * 스크립트가 INCR 로 이 값을 넘긴 뒤 DECR 로 되돌리므로 값이 자라지도 않는다.
     */
    private static final String EXHAUSTED = String.valueOf(Integer.MAX_VALUE);

    /*
     * 첫 커넥션을 세우는 데 쓰는 재시도다. 붙고 나면 다시 안 쓴다.
     * 10회에 100밀리초 간격이라 최악 1초를 쓴다. maxDuration 안에 넉넉히 들어간다.
     */
    private static final int CONNECT_ATTEMPTS = 10;
    private static final Duration CONNECT_BACKOFF = Duration.ofMillis(100);

    /*
     * 워밍업 카운터의 수명이다. 기동할 때마다 다시 쓰므로 영구로 둘 이유가 없다.
     *
     * 영구 키로 두면 둘이 걸린다. 워밍업 쿠폰 ID 를 바꿔 배포할 때마다 옛 키가 하나씩 남고,
     * ElastiCache 기본 축출 정책이 volatile-lru 라 만료가 없는 이 키는 축출 대상에서 빠진다.
     * 메모리가 찼을 때 실제 발급 상태인 seq 와 pending 이 먼저 날아간다.
     *
     * maxDuration 이 60초라 한 시간이면 워밍업이 끝나고도 한참 남는다.
     */
    private static final Duration EXHAUSTED_TTL = Duration.ofHours(1);

    private final CouponWarmupProperties properties;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;

    /*
     * 실제로 바인딩된 포트를 웹서버에 물어본다.
     * server.port 설정값을 읽으면 0(임의 포트)일 때 틀린 곳으로 보낸다.
     */
    private final WebServerApplicationContext webServerContext;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            connectRedis();
            markExhausted();
            Result result = warmUp();
            log.info("event=COUPON_WARMUP_DONE sent={} ok={} elapsedMs={}",
                    result.sent(), result.ok(), elapsedMillis(startedAt));
        } catch (Exception e) {
            /*
             * 삼킨다. 워밍업은 최적화이지 정합성 요건이 아니다.
             *
             * 여기서 던지면 인스턴스가 ready 를 못 받고, ALB 대상이 healthy 가 안 되어
             * coupon-event.sh open 의 healthy 대기(상한 600초)가 실패한다. 이벤트를 못 연다.
             *
             * 전용 ASG 는 health_check_type 이 EC2 라 그 인스턴스를 죽이지는 않는다. 살아서
             * 트래픽만 못 받으므로 자동 복구가 없고, 이 로그가 유일한 단서다.
             */
            log.warn("event=COUPON_WARMUP_FAILED elapsedMs={}", elapsedMillis(startedAt), e);
        }
    }

    /*
     * 첫 Redis 명령을 보내기 전에 커넥션부터 세운다.
     *
     * 이 JVM 의 첫 명령은 DNS 조회와 TCP 핸드셰이크와 Lettuce 초기화를 함께 문다. 그 전부가
     * 명령 타임아웃(운영 100ms) 안에 끝나야 하는데, 차가운 JVM 에서는 자주 못 끝낸다.
     *
     * 실제로 그렇게 죽었다 (2026-08-31 배포). 세 인스턴스 모두 markExhausted 에서
     * "Connection initialization timed out after 100 millisecond(s)" 로 끊겨 워밍업이
     * 요청을 한 건도 못 보냈다.
     *
     * 타임아웃을 올려서 풀지 않는다. 100ms 는 SLO 에서 역산한 값이고(왕복 2회 + 확정 대기
     * 800ms = 1초), 이 문제는 정상 상태가 아니라 최초 1회다. 한 번 붙으면 DNS 가 캐시되고
     * Lettuce 가 커넥션을 재사용하므로 다시 걸리지 않는다.
     *
     * 이 재시도가 워밍업만의 이야기가 아니다. 워밍업이 없으면 그 첫 실패를 실제 사용자의
     * 첫 요청이 문다. 여기서 미리 무는 것이 이 메서드의 값어치다.
     */
    private void connectRedis() {
        for (int attempt = 1; ; attempt++) {
            try {
                redisTemplate.hasKey(counterKey());
                if (attempt > 1) {
                    log.info("event=COUPON_WARMUP_REDIS_CONNECTED attempts={}", attempt);
                }
                return;
            } catch (DataAccessException e) {
                if (attempt >= CONNECT_ATTEMPTS) {
                    throw e;
                }
                sleepQuietly(CONNECT_BACKOFF);
            }
        }
    }

    /*
     * 이 쿠폰을 소진 상태로 세운다. 워밍업 요청이 발급까지 가면 안 된다.
     *
     * 발급되면 member_coupon 에 행이 생기고 fk_mc_member 가 워밍업용 회원 행을 요구한다.
     * 소진에서 멈추면 순번 확보까지는 다 지나면서 DB 에는 아무것도 안 쓴다.
     */
    private void markExhausted() {
        redisTemplate.opsForValue().set(counterKey(), EXHAUSTED, EXHAUSTED_TTL);
    }

    private String counterKey() {
        return "coupon:" + properties.couponId() + ":counter";
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Result warmUp() throws InterruptedException {
        String token = jwtTokenProvider.createAccessToken(WARMUP_MEMBER_ID, TokenType.MEMBER, ROLE);
        int port = webServerContext.getWebServer().getPort();
        URI uri = URI.create("http://127.0.0.1:" + port + "/v1/coupons/" + properties.couponId() + "/issues");
        long deadline = System.nanoTime() + properties.maxDuration().toNanos();

        AtomicInteger sent = new AtomicInteger();
        AtomicInteger ok = new AtomicInteger();
        Semaphore inFlight = new Semaphore(properties.concurrency());

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int i = 0; i < properties.requests() && System.nanoTime() < deadline; i++) {
                inFlight.acquire();
                workers.execute(() -> {
                    try {
                        send(client, uri, token);
                        ok.incrementAndGet();
                    } catch (Exception e) {
                        // 한 건의 실패로 워밍업 전체를 멈추지 않는다. 데우는 것이 목적이다
                    } finally {
                        sent.incrementAndGet();
                        inFlight.release();
                    }
                });
            }
            workers.shutdown();
            long left = Math.max(0, deadline - System.nanoTime());
            if (!workers.awaitTermination(left, TimeUnit.NANOSECONDS)) {
                workers.shutdownNow();
                log.warn("event=COUPON_WARMUP_TIMEOUT sent={}", sent.get());
            }
        }
        return new Result(sent.get(), ok.get());
    }

    private void send(HttpClient client, URI uri, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        // 응답 코드는 보지 않는다. 최종 소진(410)이 정상이고, 무엇이 오든 경로는 지나갔다
        client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record Result(int sent, int ok) {
    }
}
