package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.freshmarket.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/*
 * 확정 표시를 파이프라인으로 묶은 것이 얼마를 아끼는지 잰다.
 *
 * 이 값이 필요한 이유가 있다. 요청 예산의 계층 산식이 이 호출을 "Redis 왕복 하나" 로 세는데,
 * 그 전제가 실물에서 성립하는지를 숫자로 남겨야 다음에 같은 판단을 추정으로 하지 않는다.
 * 묶기 전에는 왕복이 둘이라 안쪽 합이 820ms 로 예산 800ms 를 넘고 있었다.
 *
 * 이 시험이 재는 것과 못 재는 것을 갈라 둔다.
 *
 *   잰다      명령 둘을 순차로 칠 때와 스크립트 하나로 보낼 때의 차이. 즉 왕복 하나의 값이다
 *   못 잰다   운영에서의 분포. 그쪽은 ElastiCache 왕복과 앱의 CPU 경합이 섞이고,
 *             그 둘은 로컬 컨테이너로 못 만든다. 부하 회차에서 따로 봐야 한다
 *
 * 절대값을 믿으면 안 된다. 로컬 컨테이너 왕복이 1밀리초를 넘는 것 자체가 이상하고,
 * Testcontainers 와 도커 네트워크가 섞인 값이다. 같은 실행 안의 상대 비교만 뜻이 있다.
 *
 * 배치 크기를 나눠 재는 것은 명령 개수가 회원 수와 무관하다는 것을 함께 보이기 위해서다.
 * 회원이 몇이든 명령은 둘이고, 늘어나는 것은 한 명령이 싣는 인자 수뿐이다.
 *
 * 처음에는 파이프라인으로 묶었다가 이 시험이 그것을 뒤집었다. Lettuce 는 공유 커넥션으로
 * 파이프라인을 못 해서 스프링이 전용 커넥션을 따로 얻는데, 커넥션 풀이 없어 호출마다 새
 * 연결이 열린다. 명령이 하나도 없는 빈 파이프라인이 4.6밀리초였고, 순차 왕복 둘보다 느렸다.
 */
@SpringBootTest
class CouponSeqMarkCommittedLatencyIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 4344L;

    private static final String SEQ = "coupon:4344:seq";
    private static final String PENDING = "coupon:4344:pending";
    private static final String FREE = "coupon:4344:free";
    private static final String COUNTER = "coupon:4344:counter";

    private static final int WARMUP = 200;
    private static final int ROUNDS = 100;
    private static final int[] BATCH_SIZES = {1, 50, 500};

    private static final Path REPORT = Path.of("build", "tmp", "coupon-seq-mark-committed-latency.txt");

    @Autowired
    private CouponSeqCommitter committer;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private long nextMember = 1L;

    @BeforeEach
    void 키를_비운다() {
        redisTemplate.delete(List.of(SEQ, PENDING, FREE, COUNTER));
    }

    @Test
    void 스크립트가_왕복_하나를_아낀다() throws IOException {
        워밍업한다();

        StringBuilder report = new StringBuilder("확정 표시 한 번의 지연 (마이크로초)\n\n");
        report.append("배치   방식        p50      p90      p99      max\n");

        long 묶은_p50_최소 = Long.MAX_VALUE;
        long 순차_p50_최소 = Long.MAX_VALUE;

        for (int size : BATCH_SIZES) {
            long[] 스크립트 = new long[ROUNDS];
            long[] 순차 = new long[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                // 같은 반복문 안에서 번갈아 잰다. 따로 재면 뒤엣것이 더 데워진 상태로 유리해진다
                스크립트[i] = 잰다(size, true);
                순차[i] = 잰다(size, false);
            }
            Arrays.sort(스크립트);
            Arrays.sort(순차);
            report.append(줄("%,4d   스크립트  ", size, 스크립트));
            report.append(줄("%,4d   순차      ", size, 순차));
            묶은_p50_최소 = Math.min(묶은_p50_최소, p(스크립트, 50));
            순차_p50_최소 = Math.min(순차_p50_최소, p(순차, 50));
        }

        report.append("\n워밍업 %d회, 크기마다 측정 %d회\n".formatted(WARMUP, ROUNDS));
        report.append("""

                이 표가 재는 것은 왕복 하나의 값이다. 운영에서는 여기에 ElastiCache 왕복과
                앱의 CPU 경합이 더해지고, 그 둘은 부하 회차에서 따로 봐야 한다.
                """);

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString());
        System.out.println(report);

        // 잰 값이 뜻을 가지려면 왕복 하나인 쪽이 실제로 더 빨라야 한다
        assertThat(묶은_p50_최소).isLessThan(순차_p50_최소);
    }

    /*
     * JIT 과 커넥션과 버퍼를 걷어낸다.
     * 첫 호출에는 그것들이 함께 실려 재려는 값보다 훨씬 크게 나온다.
     */
    private void 워밍업한다() {
        for (int i = 0; i < WARMUP; i++) {
            잰다(1, true);
            잰다(1, false);
        }
    }

    private long 잰다(int size, boolean 스크립트인가) {
        Map<Long, Integer> batch = 배치를_만든다(size);
        long start = System.nanoTime();
        if (스크립트인가) {
            committer.markCommitted(COUPON_ID, batch);
        } else {
            순차로_친다(batch);
        }
        return (System.nanoTime() - start) / 1_000;
    }

    /*
     * 스크립트로 바꾸기 전의 구현이다. 명령 둘을 따로 보내고 각각 응답을 기다린다.
     * 이것을 시험 안에 두는 이유는 비교 대상이 사라졌기 때문이다. 구현을 되돌려 두 번 돌리면
     * 그사이에 다른 것이 달라졌는지 알 수 없다. 같은 실행 안에서 번갈아 재야 조건이 같다.
     */
    private void 순차로_친다(Map<Long, Integer> seqByMember) {
        Map<String, String> fields = new HashMap<>(seqByMember.size());
        seqByMember.forEach((memberId, seq) -> fields.put(String.valueOf(memberId), seq + ":1"));
        redisTemplate.opsForHash().putAll(SEQ, fields);
        redisTemplate.opsForZSet().remove(PENDING, fields.keySet().toArray());
    }

    // 회원을 매번 바꾼다. 같은 회원을 다시 쓰면 해시가 이미 그 필드를 갖고 있어 경로가 달라진다
    private Map<Long, Integer> 배치를_만든다(int size) {
        Map<Long, Integer> batch = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            long member = nextMember++;
            batch.put(member, (int) (member % 1_000_000) + 1);
        }
        return batch;
    }

    private static String 줄(String 앞, int size, long[] sorted) {
        return 앞.formatted(size)
                + "%,7d  %,7d  %,7d  %,7d%n".formatted(
                        p(sorted, 50), p(sorted, 90), p(sorted, 99), sorted[sorted.length - 1]);
    }

    private static long p(long[] sorted, int percentile) {
        int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
        return sorted[Math.max(0, index)];
    }

    /*
     * 매핑 삭제와 번호 되돌리기도 같은 방식으로 잰다.
     *
     * 되돌리기는 티켓마다 한 번이라 배치 크기가 늘 1 이다. 대신 왕복이 다섯이라 줄어드는 폭이
     * 제일 크고, 그중 둘이 수명 물려주기다. 앱에서는 남은 시간을 읽어 상대 시각으로 다시 걸어야
     * 했는데, 스크립트 안에서는 절대 시각을 그대로 옮길 수 있어 어긋남도 없어진다.
     */
    @Test
    void 뒷정리_둘도_왕복이_준다() throws IOException {
        redisTemplate.opsForValue().set(COUNTER, "0");
        redisTemplate.expire(COUNTER, java.time.Duration.ofMinutes(10));
        for (int i = 0; i < WARMUP; i++) {
            committer.dropMappings(COUPON_ID, List.of(nextMember++));
            매핑을_순차로_지운다(List.of(nextMember++));
            committer.returnAndRepairs(COUPON_ID, List.of(new CouponSeqCommitter.Repair(nextMember++, 1, 2)));
            번호를_순차로_되돌린다(nextMember++, 1, 2);
        }

        StringBuilder report = new StringBuilder("\n매핑 삭제와 번호 되돌리기 (마이크로초)\n\n");
        report.append("배치   방식        p50      p90      p99      max\n");

        for (int size : BATCH_SIZES) {
            long[] 스크립트 = new long[ROUNDS];
            long[] 순차 = new long[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                List<Long> a = 회원들(size);
                long t0 = System.nanoTime();
                committer.dropMappings(COUPON_ID, a);
                스크립트[i] = (System.nanoTime() - t0) / 1_000;

                List<Long> b = 회원들(size);
                long t1 = System.nanoTime();
                매핑을_순차로_지운다(b);
                순차[i] = (System.nanoTime() - t1) / 1_000;
            }
            Arrays.sort(스크립트);
            Arrays.sort(순차);
            report.append(줄("%,4d   삭제 스크립트", size, 스크립트));
            report.append(줄("%,4d   삭제 순차    ", size, 순차));
        }

        long[] 되돌리기_스크립트 = new long[ROUNDS];
        long[] 되돌리기_순차 = new long[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            long t0 = System.nanoTime();
            committer.returnAndRepairs(COUPON_ID, List.of(new CouponSeqCommitter.Repair(nextMember++, 1, 2)));
            되돌리기_스크립트[i] = (System.nanoTime() - t0) / 1_000;

            long t1 = System.nanoTime();
            번호를_순차로_되돌린다(nextMember++, 1, 2);
            되돌리기_순차[i] = (System.nanoTime() - t1) / 1_000;
        }
        Arrays.sort(되돌리기_스크립트);
        Arrays.sort(되돌리기_순차);
        report.append(줄("   1   되돌 스크립트", 1, 되돌리기_스크립트));
        report.append(줄("   1   되돌 순차    ", 1, 되돌리기_순차));
        report.append("\n왕복  삭제 2 -> 1,  되돌리기 5 -> 1\n");

        System.out.println(report);
        Files.writeString(Path.of("build", "tmp", "coupon-seq-cleanup-latency.txt"), report.toString());

        assertThat(p(되돌리기_스크립트, 50)).isLessThan(p(되돌리기_순차, 50));
    }

    // 스크립트로 묶기 전의 매핑 삭제다
    private void 매핑을_순차로_지운다(List<Long> memberIds) {
        Object[] members = memberIds.stream().map(String::valueOf).toArray();
        redisTemplate.opsForHash().delete(SEQ, members);
        redisTemplate.opsForZSet().remove(PENDING, members);
    }

    /*
     * 스크립트로 묶기 전의 번호 되돌리기다.
     * 가운데 둘이 수명 물려주기인데, 절대 만료 시각을 읽는 명령을 스프링이 안 내줘서
     * 남은 시간을 읽어 상대 시각으로 다시 걸어야 했다.
     */
    private void 번호를_순차로_되돌린다(long memberId, int burnedSeq, int actualSeq) {
        String member = String.valueOf(memberId);
        redisTemplate.opsForZSet().add(FREE, String.valueOf(burnedSeq), burnedSeq);
        Long remaining = redisTemplate.getExpire(COUNTER, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (remaining != null && remaining > 0) {
            redisTemplate.expire(FREE, java.time.Duration.ofMillis(remaining));
        }
        redisTemplate.opsForHash().put(SEQ, member, actualSeq + ":1");
        redisTemplate.opsForZSet().remove(PENDING, member);
    }

    private List<Long> 회원들(int size) {
        List<Long> members = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            members.add(nextMember++);
        }
        return members;
    }
}
