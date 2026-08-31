package com.freshmarket.coupon.domain.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import com.freshmarket.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/*
 * 서버 스크립트 캐시가 비어 있을 때 첫 순번 확보가 얼마나 더 걸리는지 잰다.
 *
 * 이벤트를 열 때 SCRIPT LOAD 로 미리 올려 두던 장치를 걷으면서, 그 장치가 아끼던 값이 얼마인지를
 * 숫자로 남긴다. 걷을 때는 왕복 시간에서 계산한 추정치뿐이었고, 재 보니 네 배였다.
 *
 * 이 값이 남아 있어야 같은 판단을 다시 할 때 추정으로 돌아가지 않는다. 지금은 기동 워밍업이
 * 캐시를 채우므로 이 비용을 무는 경우가 좁아졌지만, 기동과 이벤트 사이에 Redis 가 재시작하면
 * 그때는 이벤트의 첫 요청 수십 건이 이만큼을 더 문다.
 *
 * 캐시가 비면 스프링이 EVALSHA 로 보냈다 NOSCRIPT 로 튕긴 뒤 EVAL 로 본문을 다시 싣는다.
 * 그 대가를 무는 것은 첫 EVAL 이 돌아오기 전에 날아간 요청들뿐이고, 그 뒤는 전부 EVALSHA 다.
 */
@SpringBootTest
class CouponSeqScriptCacheLatencyIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 4343L;
    private static final int ISSUE_LIMIT = 1_000_000;

    private static final String SEQ = "coupon:4343:seq";
    private static final String FREE = "coupon:4343:free";
    private static final String COUNTER = "coupon:4343:counter";
    private static final String PENDING = "coupon:4343:pending";

    private static final int WARMUP = 200;
    private static final int ROUNDS = 30;

    private static final Path REPORT = Path.of("build", "tmp", "coupon-seq-script-cache-latency.txt");

    @Autowired
    private CouponSeqAllocator allocator;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private long nextMember = 1L;

    @BeforeEach
    void 이벤트를_연다() {
        redisTemplate.delete(List.of(SEQ, FREE, COUNTER, PENDING));
        redisTemplate.opsForValue().set(COUNTER, "0");
    }

    @Test
    void 캐시가_빈_첫_호출의_추가_비용을_잰다() throws IOException {
        워밍업한다();

        long[] cold = new long[ROUNDS];
        long[] warm = new long[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            // 캐시를 비우면 EVALSHA -> NOSCRIPT -> EVAL 이 된다
            캐시를_비운다();
            cold[i] = 한_번_잰다();
            // 방금 EVAL 이 채워 두었으므로 이번에는 EVALSHA 하나로 끝난다
            warm[i] = 한_번_잰다();
        }

        Arrays.sort(cold);
        Arrays.sort(warm);
        String report = """
                순번 확보 한 번의 지연 (마이크로초)

                          p50      p90      max
                캐시 없음  %,7d  %,7d  %,7d
                캐시 있음  %,7d  %,7d  %,7d
                차이      %,7d

                워밍업 %d회, 측정 %d회
                """.formatted(
                p(cold, 50), p(cold, 90), cold[ROUNDS - 1],
                p(warm, 50), p(warm, 90), warm[ROUNDS - 1],
                p(cold, 50) - p(warm, 50), WARMUP, ROUNDS);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        System.out.println(report);

        // 잰 값이 뜻을 가지려면 캐시를 비운 쪽이 실제로 더 느려야 한다
        assertThat(p(cold, 50)).isGreaterThan(p(warm, 50));
    }

    /*
     * JIT 과 커넥션과 버퍼를 걷어낸다.
     * 첫 호출에는 그것들이 함께 실려 재려는 값보다 훨씬 크게 나온다.
     */
    private void 워밍업한다() {
        for (int i = 0; i < WARMUP; i++) {
            한_번_잰다();
        }
    }

    // 회원을 매번 바꾼다. 같은 회원이면 스크립트가 HGET 에서 곧바로 돌아가 경로가 짧아진다
    private long 한_번_잰다() {
        long member = nextMember++;
        long start = System.nanoTime();
        allocator.allocate(COUPON_ID, member, ISSUE_LIMIT);
        return (System.nanoTime() - start) / 1_000;
    }

    private void 캐시를_비운다() {
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.scriptingCommands().scriptFlush();
            return null;
        });
    }

    private static long p(long[] sorted, int percentile) {
        int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
        return sorted[Math.max(0, index)];
    }

    // 시험이 실제 스크립트를 쓰는지 확인한다. 파일이 어긋나면 위 측정이 딴 것을 잰다
    @Test
    void 스크립트_파일을_읽을_수_있다() throws Exception {
        try (var in = new ClassPathResource("redis/scripts/coupon-issue-seq.lua").getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(RedisScript.of(body, String.class).getSha1()).isNotBlank();
        }
    }
}
