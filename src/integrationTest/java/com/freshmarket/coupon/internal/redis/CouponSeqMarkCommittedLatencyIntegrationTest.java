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

    // 한 회분에 몰릴 수 있는 중복 해소 건수다. 마지막 값이 batch-size 와 같은 최악이다
    private static final int[] 회분_중복_수 = {1, 10, 50, 500};

    /*
     * 낱개 쪽이 회차마다 최대 500 번을 돌아 앞의 시험들보다 한 회차가 비싸다.
     * 그래도 100 회를 도는 이유는 30 회에서는 p99 가 최댓값 한 건과 같아져 튄 값 하나가
     * 그대로 표에 오르기 때문이다.
     */
    private static final int 회분_반복 = 100;

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
        report.append("전 = 명령 둘을 순차로 보낸다,  후 = 스크립트 하나로 보낸다.\n\n");
        report.append("                        p50                        p90\n");
        report.append("배치            전        후     배수        전        후     배수\n");

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
            report.append(비교_줄("%,4d      ".formatted(size), 순차, 스크립트));
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

    private static long p(long[] sorted, int percentile) {
        int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
        return sorted[Math.max(0, index)];
    }

    /*
     * 매핑 삭제와 번호 되돌리기도 같은 방식으로 잰다.
     *
     * 되돌리기를 여기서 크기 1 로만 재는 이유는, 이 시험이 재는 것이 왕복 하나의 값이어서다.
     * 한 회분에 여러 건이 몰릴 때 무엇이 달라지는지는 아래 셋째 시험이 따로 잰다.
     * 되돌리기는 왕복이 다섯이라 줄어드는 폭이 제일 크고, 그중 둘이 수명 물려주기다. 앱에서는 남은 시간을 읽어 상대 시각으로 다시 걸어야
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
        report.append("전 = 명령을 하나씩 보낸다,  후 = 스크립트 하나로 보낸다.\n\n");
        report.append("                              p50                        p90\n");
        report.append("배치  경로              전        후     배수        전        후     배수\n");

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
            report.append(비교_줄("%,4d  매핑 삭제  ".formatted(size), 순차, 스크립트));
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
        report.append(비교_줄("   1  번호 되돌리기", 되돌리기_순차, 되돌리기_스크립트));
        report.append("\n왕복  매핑 삭제 2 -> 1,  번호 되돌리기 5 -> 1\n");

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

    /*
     * 한 회분에 중복 해소가 여러 건 몰릴 때 묶어 보내는 것이 얼마를 아끼는지 잰다.
     *
     * 앞의 두 시험과 재는 축이 다르다. 그쪽은 같은 크기의 호출 하나를 순차 명령과 스크립트로
     * 견주고, 이쪽은 크기 1 호출을 N 번 보내는 것과 크기 N 호출을 한 번 보내는 것을 견준다.
     * 스크립트로 바꾼 뒤에도 이 둘은 남아 있던 차이라 따로 재야 한다.
     *
     * 두 경로를 나눠 재는 이유는 왕복 수가 달라서다. 확정 표시는 건마다 왕복 하나이고
     * 번호 되돌리기는 건마다 왕복 하나이되 스크립트 안에서 명령 다섯을 편다.
     *
     * 확정 표시의 "후" 는 실제보다 비싸게 잡은 값이다. 운영에서는 커밋에 성공한 티켓들이
     * 이미 보내는 호출에 얹히므로 늘어나는 것이 인자뿐이고, 여기서는 그 호출을 따로 세운다.
     */
    @Test
    void 한_회분에_몰린_중복_해소를_묶으면_준다() throws IOException {
        redisTemplate.opsForValue().set(COUNTER, "0");
        redisTemplate.expire(COUNTER, java.time.Duration.ofMinutes(10));
        for (int i = 0; i < WARMUP; i++) {
            committer.markCommitted(COUPON_ID, Map.of(nextMember++, 1));
            committer.returnAndRepairs(COUPON_ID, List.of(되돌릴_것들(1).get(0)));
        }

        StringBuilder report = new StringBuilder("\n한 회분에 몰린 중복 해소 (마이크로초)\n\n");
        report.append("한 줄이 호출 하나가 아니라 한 회분 전체의 뒷정리 시간이다.\n");
        report.append("전 = 티켓마다 따로 보낸다,  후 = 한 회분을 한 번에 보낸다.\n\n");
        report.append("                        p50                        p90\n");
        report.append("중복  경로          전        후     배수        전        후     배수\n");

        StringBuilder 꼬리 = new StringBuilder("\n최악 한 회차 (max, 마이크로초)\n\n");
        꼬리.append("중복  경로          전        후     배수\n");

        long 마지막_확정_전 = 0;
        long 마지막_확정_후 = 0;
        long 마지막_되돌_전 = 0;
        long 마지막_되돌_후 = 0;

        for (int n : 회분_중복_수) {
            long[] 확정_전 = new long[회분_반복];
            long[] 확정_후 = new long[회분_반복];
            long[] 되돌_전 = new long[회분_반복];
            long[] 되돌_후 = new long[회분_반복];

            for (int r = 0; r < 회분_반복; r++) {
                List<Long> 하나씩 = 회원들(n);
                long t0 = System.nanoTime();
                for (Long member : 하나씩) {
                    committer.markCommitted(COUPON_ID, Map.of(member, 1));
                }
                확정_전[r] = (System.nanoTime() - t0) / 1_000;

                Map<Long, Integer> 한번에 = new HashMap<>(n);
                for (Long member : 회원들(n)) {
                    한번에.put(member, 1);
                }
                long t1 = System.nanoTime();
                committer.markCommitted(COUPON_ID, 한번에);
                확정_후[r] = (System.nanoTime() - t1) / 1_000;

                List<CouponSeqCommitter.Repair> 낱개 = 되돌릴_것들(n);
                long t2 = System.nanoTime();
                for (CouponSeqCommitter.Repair repair : 낱개) {
                    committer.returnAndRepairs(COUPON_ID, List.of(repair));
                }
                되돌_전[r] = (System.nanoTime() - t2) / 1_000;

                List<CouponSeqCommitter.Repair> 묶음 = 되돌릴_것들(n);
                long t3 = System.nanoTime();
                committer.returnAndRepairs(COUPON_ID, 묶음);
                되돌_후[r] = (System.nanoTime() - t3) / 1_000;
            }

            Arrays.sort(확정_전);
            Arrays.sort(확정_후);
            Arrays.sort(되돌_전);
            Arrays.sort(되돌_후);
            report.append(비교_줄("%,4d  확정 표시".formatted(n), 확정_전, 확정_후));
            report.append(비교_줄("%,4d  되돌리기 ".formatted(n), 되돌_전, 되돌_후));
            꼬리.append(최악_줄("%,4d  확정 표시".formatted(n), 확정_전, 확정_후));
            꼬리.append(최악_줄("%,4d  되돌리기 ".formatted(n), 되돌_전, 되돌_후));

            마지막_확정_전 = p(확정_전, 50);
            마지막_확정_후 = p(확정_후, 50);
            마지막_되돌_전 = p(되돌_전, 50);
            마지막_되돌_후 = p(되돌_후, 50);
        }

        report.append("\n왕복  중복 N 건에 대해 확정 표시 N -> 1,  되돌리기 N -> 1\n");
        report.append("""
                배수가 1 에 가까운 줄은 이 변경이 안 도움 되는 자리다.
                거기서 비용도 안 든다는 것을 함께 보려고 중복 1 을 남겨 둔다.
                꼬리는 %,d 회 중 최악 한 회차로 따로 본다. p90 이 그것까지는 안 보여 준다.
                """.formatted(회분_반복));
        report.append(꼬리);

        System.out.println(report);
        Files.writeString(Path.of("build", "tmp", "coupon-seq-batching-latency.txt"), report.toString());

        assertThat(마지막_확정_후).isLessThan(마지막_확정_전);
        assertThat(마지막_되돌_후).isLessThan(마지막_되돌_전);
    }

    private static String 비교_줄(String label, long[] 전, long[] 후) {
        return "%s  %,8d  %,8d  %5.1f배  %,8d  %,8d  %5.1f배%n".formatted(label,
                p(전, 50), p(후, 50), 배수(p(전, 50), p(후, 50)),
                p(전, 90), p(후, 90), 배수(p(전, 90), p(후, 90)));
    }

    private static String 최악_줄(String label, long[] 전, long[] 후) {
        long a = 전[전.length - 1];
        long b = 후[후.length - 1];
        return "%s  %,8d  %,8d  %5.1f배%n".formatted(label, a, b, 배수(a, b));
    }

    // 후가 더 느린 회차도 그대로 보이도록 1 미만도 자르지 않는다
    private static double 배수(long 전, long 후) {
        return 전 / (double) Math.max(1, 후);
    }

    private List<CouponSeqCommitter.Repair> 되돌릴_것들(int size) {
        List<CouponSeqCommitter.Repair> repairs = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long member = nextMember++;
            repairs.add(new CouponSeqCommitter.Repair(member, (int) member, (int) member + 1));
        }
        return repairs;
    }
}
