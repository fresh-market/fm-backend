package com.freshmarket.coupon.internal.redis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.internal.entity.Coupon;
import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponSeqRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponSeqRepository.IssuedSeq;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 가 이벤트의 키를 잃었거나 승격으로 값이 뒤로 갔을 때 다시 세운다. 설계 근거는
 * {@code docs/coupon/rebuild-raise-each-2026-10-14.md} 에 있다.
 *
 * <p><b>발급을 멈추지 않는다.</b> 예전에는 재건 락이 있는 동안 순번 확보가 {@code -2} 를 내
 * 그 쿠폰의 발급이 통째로 멈췄고, 그 정지가 N-2 에서 0.960초, P-1 에서 1.287초였다. 지금은
 * 멈출 이유가 셋 다 사라졌다 (같은 문서 5장).
 *
 * <p><b>플러시도 멈추지 않는다.</b> 멈춤이 필요했던 까닭은 기여자가 확정 표시를 덮기 때문이었고,
 * 쓰기를 "없을 때만" 으로 바꾸어 그 덮어쓰기를 구조적으로 없앴다.
 *
 * <p><b>아무도 세지 않는다.</b> 명부도 기여 수집도 완료 표시도 기다림도 없다. 이 세션의 결함
 * 다섯이 모두 "주도자가 참가자를 세는 데 실패한 것" 이었다.
 *
 * <p>두 단계로 나뉜다.
 *
 * <pre>
 * A  재연결을 본 각 인스턴스가 카운터를 올리고 자기 큐를 되살린다. 락도 기다림도 없다
 * B  락을 잡은 한 대가 free 를 세운다. 락은 선출에만 쓰고 발급을 안 막는다
 * </pre>
 *
 * <p><b>A 가 끝나면 문이 열려 있다.</b> 카운터를 세우는 것이 곧 문을 여는 동작이고, {@code free}
 * 는 문을 여는 데 필요하지 않다.
 *
 * <p><b>안전은 이 클래스가 지키는 것이 아니다.</b> 초과 발급과 1인 2매는
 * {@code uk_mc_coupon_seq} 와 {@code uk_mc_coupon_member} 가 막는다. 카운터가 낮게 서도 그
 * 번호가 두 번 나갈 뿐이고 한쪽이 유니크 키에 걸려 결번으로 남는다. <b>이 클래스가 줄이는 것은
 * 결번이고 품질이다.</b>
 */
@Slf4j
@Component
public class CouponSeqRebuilder {

    private static final String OPEN_GATE_SCRIPT_PATH = "redis/scripts/coupon-open-gate.lua";

    private static final String WRITE_FREE_SCRIPT_PATH = "redis/scripts/coupon-write-free.lua";

    private static final String COMMITTED_SUFFIX = ":1";

    /*
     * 한 번에 Redis 로 보내는 항목 수다.
     * 만 건을 한 명령에 담으면 그동안 Redis 단일 스레드가 다른 요청을 못 받는다.
     */
    private static final int CHUNK = 500;

    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;
    private final MemberCouponSeqRepository seqRepository;
    private final CouponSeqInitializer seqInitializer;
    private final CouponSeqContributor contributor;
    private final CouponSeqMarks marks;
    private final int freeMargin;
    private final Duration lockTtl;
    private final Timer raiseDuration;
    private final Timer freeDuration;
    private final RedisScript<Long> openGateScript;
    private final RedisScript<Long> writeFreeScript;

    public CouponSeqRebuilder(CouponSeqRebuildRedis rebuildRedis,
                              CouponRepository couponRepository,
                              MemberCouponSeqRepository seqRepository,
                              CouponSeqInitializer seqInitializer,
                              CouponSeqContributor contributor,
                              CouponSeqMarks marks,
                              CouponIssueProperties properties,
                              MeterRegistry registry) {
        this.redisTemplate = rebuildRedis.template();
        this.couponRepository = couponRepository;
        this.seqRepository = seqRepository;
        this.seqInitializer = seqInitializer;
        this.contributor = contributor;
        this.marks = marks;
        this.freeMargin = properties.freeMargin();
        /*
         * 락은 선출에만 쓴다. 잡은 대가 죽어도 다음 재연결이 다시 잡을 수 있게 짧게 둔다.
         * 발급을 막지 않으므로 이 값이 길어도 사용자 응답에 새지 않는다.
         */
        this.lockTtl = Duration.ofSeconds(30);
        /*
         * A 단계가 문을 여는 데 걸린 시간이다.
         *
         * 이것은 정지 시간이 아니다. 예전에는 재건이 락으로 발급을 막았으므로 그 값이 곧 정지였고
         * 0.244 ~ 1.287초였다. 지금은 막는 것이 없어 이름을 정지가 아니라 소요로 읽어야 한다.
         */
        this.raiseDuration = Timer.builder("coupon.seq.rebuild.raise")
                .description("카운터를 올리고 자기 큐를 되살린 시간. 발급을 막지 않는다")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.freeDuration = Timer.builder("coupon.seq.rebuild.free")
                .description("주인 잃은 번호를 골라 free 에 넣은 시간. 발급을 막지 않는다")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.openGateScript = loadScript(OPEN_GATE_SCRIPT_PATH);
        this.writeFreeScript = loadScript(WRITE_FREE_SCRIPT_PATH);
    }

    /*
     * 기동 때 읽는다. 못 읽으면 기동이 실패한다.
     * CouponSeqCommitter 와 CouponSeqAllocator 가 같은 이유로 같은 모양을 쓴다.
     */
    private static RedisScript<Long> loadScript(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return RedisScript.of(new String(in.readAllBytes(), StandardCharsets.UTF_8), Long.class);
        } catch (IOException e) {
            throw new IllegalStateException(path + " 를 읽지 못했다", e);
        }
    }

    /**
     * 이 쿠폰의 키를 다시 세운다. 같은 쿠폰에 여러 인스턴스가 동시에 들어와도 된다.
     *
     * <p><b>깨우는 신호는 손실만 뜻하지 않는다.</b> 관리자가 아직 안 연 이벤트도 {@code -2} 를
     * 내므로 그 둘을 DB 로 가른다.
     */
    public void rebuild(long couponId) {
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null || !coupon.isActive() || !coupon.isLimited()) {
            // 관리자가 아직 안 열었거나 선착순 쿠폰이 아니다. 카운터가 없는 것이 정상이다
            return;
        }
        if (reprepared(couponId)) {
            log.info("event=COUPON_SEQ_REBUILD_SKIPPED couponId={} reason=reprepared", couponId);
            return;
        }

        List<IssuedSeq> issued = raise(couponId, coupon);
        writeFreeIfElected(couponId, issued);
    }

    /**
     * A 단계다. 카운터를 올리고 커밋된 매핑과 자기 큐를 되살린다.
     *
     * <p><b>순서가 곧 안전장치다.</b> 카운터를 세우는 것이 문을 여는 동작이라 가장 마지막이다.
     * 먼저 열면 이미 번호를 쥔 회원이 {@code seq} 에 없는 틈에 재시도해 번호를 하나 더 태운다.
     *
     * <p><b>커밋된 회원은 DB 에서 세운다.</b> 발급 경로에 DB 중복 검사가 없어서 "이미 발급됐다" 를
     * 이 해시에서만 안다. 비어 있으면 이미 받은 회원의 재시도가 번호를 하나 더 태우고, 그 행이
     * {@code uk_mc_coupon_member} 에 걸려 결번이 된다.
     *
     * @return 읽은 발급 행. B 단계가 같은 스냅숏을 쓴다
     */
    private List<IssuedSeq> raise(long couponId, Coupon coupon) {
        long startedAt = System.nanoTime();

        List<IssuedSeq> issued = seqRepository.findIssuedSeqs(couponId);
        writeSeqHash(couponId, issued);
        contributor.restore(couponId);

        int dbMax = maxSeq(issued);
        int mark = marks.of(couponId);
        int bound = Math.max(dbMax, mark);
        long opened = openGate(couponId, bound, coupon.getIssueEndAt());

        raiseDuration.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        log.warn("event=COUPON_SEQ_RAISED couponId={} issued={} dbMax={} mark={} bound={} counter={}"
                        + " raiseMillis={}",
                couponId, issued.size(), dbMax, mark, bound, opened,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        return issued;
    }

    /**
     * B 단계다. 락을 잡은 한 대만 {@code free} 를 세운다.
     *
     * <p><b>락은 선출에만 쓴다.</b> N 대가 같은 DB 읽기와 같은 계산을 중복하지 않게 하는 값이고,
     * <b>발급을 한 번도 안 막는다.</b> 못 잡은 대는 A 단계만 하고 돌아간다.
     *
     * <p><b>나이로 거른다.</b> 방금 나가 아직 큐에 있는 번호를 되살리면 살아 있는 티켓의 번호를
     * 남에게 내준다. 카운터는 번호가 나갈 때마다 하나씩 오르므로 그 차이가 "그 뒤로 몇 개가 더
     * 나갔나" 이고, 아직 A 단계를 못 한 대의 티켓도 그 조건이 덮는다 (설계 문서 5.4).
     *
     * <p><b>쥔 번호는 스크립트가 가린다.</b> {@code seq} 해시를 통째로 읽어 값으로 쓰인 번호를
     * 건너뛴다. 그 비교와 쓰기가 한 스크립트에 있어야 발급을 멈추지 않아도 안전하다.
     */
    private void writeFreeIfElected(long couponId, List<IssuedSeq> issued) {
        String token = java.util.UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(CouponSeqKeys.rebuild(couponId), token, lockTtl))) {
            // 남이 세우고 있다. A 단계는 이미 했으므로 그대로 돌아간다
            return;
        }
        long startedAt = System.nanoTime();
        try {
            int counter = currentCounter(couponId);
            List<Integer> candidates = candidates(issued, counter);
            if (candidates.isEmpty()) {
                log.info("event=COUPON_SEQ_FREE_NONE couponId={} counter={}", couponId, counter);
                return;
            }
            long added = 0;
            for (int from = 0; from < candidates.size(); from += CHUNK) {
                added += writeFree(couponId,
                        candidates.subList(from, Math.min(from + CHUNK, candidates.size())));
            }
            freeDuration.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            log.warn("event=COUPON_SEQ_FREE_WRITTEN couponId={} counter={} candidates={} added={}"
                            + " freeMillis={}",
                    couponId, counter, candidates.size(), added,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        } finally {
            releaseLock(couponId, token);
        }
    }

    /**
     * 커밋된 회원의 매핑을 다시 쓴다. 값에 확정 표시를 붙인다.
     *
     * <p><b>표시를 안 붙이면 회수가 끝난 번호를 뺏는다.</b> 미확정으로 보이면 기준 시간이 지날 때
     * 그 번호를 남에게 넘기므로, 이미 행이 된 번호가 두 번 나간다.
     *
     * <p>덮어쓴다. {@code NX} 로 쓰지 않는다. 승격 경로에서 남아 있는 옛 매핑이 그 회원을 이미
     * 남에게 간 번호로 되돌릴 수 있고, DB 행이 그보다 정확하다.
     */
    private void writeSeqHash(long couponId, List<IssuedSeq> issued) {
        if (issued.isEmpty()) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>(issued.size());
        for (IssuedSeq row : issued) {
            fields.put(String.valueOf(row.memberId()), row.issueSeq() + COMMITTED_SUFFIX);
        }
        Map<String, String> chunk = new HashMap<>(CHUNK);
        String key = CouponSeqKeys.seq(couponId);
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            chunk.put(entry.getKey(), entry.getValue());
            if (chunk.size() == CHUNK) {
                redisTemplate.opsForHash().putAll(key, chunk);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            redisTemplate.opsForHash().putAll(key, chunk);
        }
    }

    /** 되살릴 후보다. DB 에 행이 없고 카운터에서 충분히 떨어진 번호만 남긴다. */
    private List<Integer> candidates(List<IssuedSeq> issued, int counter) {
        int max = maxSeq(issued);
        if (max == 0) {
            return List.of();
        }
        boolean[] taken = new boolean[max + 1];
        for (IssuedSeq row : issued) {
            if (row.issueSeq() >= 1 && row.issueSeq() <= max) {
                taken[row.issueSeq()] = true;
            }
        }
        List<Integer> candidates = new ArrayList<>();
        for (int seq = 1; seq <= max; seq++) {
            if (!taken[seq] && counter - seq >= freeMargin) {
                candidates.add(seq);
            }
        }
        return candidates;
    }

    /*
     * 가리기와 넣기를 스크립트 하나에 담는다.
     *
     * 나눠 부르면 그 사이에 free 에서 꺼내 간 번호를 못 보고 다시 넣는다. 그러면 같은 번호가
     * 두 사람에게 나간다 (설계 문서 5.3).
     */
    private long writeFree(long couponId, List<Integer> chunk) {
        Object[] argv = chunk.stream().map(String::valueOf).toArray();
        Long added = redisTemplate.execute(writeFreeScript,
                List.of(CouponSeqKeys.seq(couponId), CouponSeqKeys.free(couponId),
                        CouponSeqKeys.counter(couponId)),
                argv);
        return added == null ? 0 : added;
    }

    /**
     * 카운터를 세워 문을 연다. 이 메서드가 도는 순간부터 요청이 번호를 받기 시작한다.
     *
     * <p><b>올리기만 한다.</b> 그 성질이 "각자 올려도 된다" 의 근거 전부다. 여러 인스턴스가 각자
     * 읽고 각자 쓰는데 읽는 순서와 쓰는 순서가 보장되지 않으므로, 낮은 값이 높은 값을 덮을 수
     * 있으면 재건이 고치려는 증상을 재건이 만든다.
     *
     * @return 쓴 뒤의 카운터. 남이 더 높게 세워 두었으면 그 값이다
     */
    private long openGate(long couponId, int bound, LocalDateTime issueEndAt) {
        Long opened = redisTemplate.execute(openGateScript,
                List.of(CouponSeqKeys.counter(couponId)), String.valueOf(bound));
        seqInitializer.applyTtl(couponId, issueEndAt);
        inheritCounterTtl(couponId, CouponSeqKeys.seq(couponId), CouponSeqKeys.free(couponId),
                CouponSeqKeys.pending(couponId));
        return opened == null ? bound : opened;
    }

    /** 나간 번호 중 가장 큰 것이다. 개수로 더하면 결번만큼 모자라게 센다. */
    private static int maxSeq(List<IssuedSeq> issued) {
        int max = 0;
        for (IssuedSeq row : issued) {
            max = Math.max(max, row.issueSeq());
        }
        return max;
    }

    private int currentCounter(long couponId) {
        String raw = redisTemplate.opsForValue().get(CouponSeqKeys.counter(couponId));
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log.warn("event=COUPON_SEQ_COUNTER_UNREADABLE couponId={} value={}", couponId, raw);
            return 0;
        }
    }

    /*
     * 넷의 수명은 counter 가 들고 나머지가 따라간다.
     * 평상시에는 순번 확보가 키를 만들면서 물려받는데, 재건은 카운터보다 먼저 만들어 그 경로를
     * 안 지난다. 그래서 여기서 직접 건다.
     */
    private void inheritCounterTtl(long couponId, String... keys) {
        Long remaining = redisTemplate.getExpire(CouponSeqKeys.counter(couponId), TimeUnit.MILLISECONDS);
        if (remaining == null || remaining <= 0) {
            return;
        }
        for (String key : keys) {
            redisTemplate.expire(key, Duration.ofMillis(remaining));
        }
    }

    /**
     * 관리자가 이벤트를 방금 다시 준비했는지 본다.
     *
     * <p>준비 단계({@code CouponSeqInitializer})가 카운터를 0 으로 세운다. 이 재건이 세울 값은
     * 나간 번호의 최댓값이라 0 이 될 수 없다. <b>0 이 보이면 그쪽이 새로 연 것이므로 덮지 않는다.</b>
     * 덮으면 지난 이벤트의 최댓값까지 건너뛰어 그만큼의 재고를 통째로 버린다.
     */
    private boolean reprepared(long couponId) {
        return "0".equals(redisTemplate.opsForValue().get(CouponSeqKeys.counter(couponId)));
    }

    /*
     * 내가 잡은 락일 때만 푼다.
     * 읽고 지우는 사이가 원자적이지 않아 아주 드물게 남의 락을 지울 수 있다. 그때도 잃는 것은
     * 없다. 락은 선출일 뿐이고 둘이 같은 free 를 세워도 NX 라 결과가 같다.
     */
    private void releaseLock(long couponId, String token) {
        String key = CouponSeqKeys.rebuild(couponId);
        if (token.equals(redisTemplate.opsForValue().get(key))) {
            redisTemplate.delete(key);
        }
    }
}
