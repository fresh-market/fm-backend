package com.freshmarket.coupon.domain.warmup;

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
     * 워밍업 쿠폰은 재고가 0 이라 요청이 순번 확보에서 소진으로 끝나고 member_coupon 에
     * 아무것도 안 쓴다. 그래서 fk_mc_member 에 걸릴 일이 없다. 토큰의 주체로만 쓰인다.
     */
    private static final long WARMUP_MEMBER_ID = -1L;

    private static final String ROLE = "ROLE_MEMBER";

    private final CouponWarmupProperties properties;
    private final JwtTokenProvider jwtTokenProvider;

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
            Result result = warmUp();
            log.info("event=COUPON_WARMUP_DONE sent={} ok={} elapsedMs={}",
                    result.sent(), result.ok(), elapsedMillis(startedAt));
        } catch (Exception e) {
            /*
             * 삼킨다. 워밍업은 최적화이지 정합성 요건이 아니다.
             * 여기서 던지면 인스턴스가 ready 가 못 되고 ASG 가 교체를 반복한다.
             */
            log.warn("event=COUPON_WARMUP_FAILED elapsedMs={}", elapsedMillis(startedAt), e);
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
        // 응답 코드는 보지 않는다. 소진(409)이 정상이고, 무엇이 오든 경로는 지나갔다
        client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record Result(int sent, int ok) {
    }
}
