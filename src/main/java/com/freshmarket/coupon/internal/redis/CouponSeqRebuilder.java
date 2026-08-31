package com.freshmarket.coupon.internal.redis;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.internal.entity.Coupon;
import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponSeqRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponSeqRepository.IssuedSeq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 가 이벤트의 키를 잃었을 때 DB 와 앱 큐로부터 다시 세운다. 절차와 근거는
 * {@code docs/coupon/coupon.md} 10장에 있고, 운영 절차는 {@code redis-promotion-rebuild.md} 다.
 *
 * <p><b>요청을 막는 장치를 새로 만들지 않는다.</b> 순번 확보 스크립트가 첫 줄에서
 * {@code counter} 가 없으면 {@code -2} 를 돌려주므로, 카운터가 없는 동안에는 아무도 번호를 못
 * 받는다. 그래서 이 클래스가 할 일은 <b>카운터를 가장 마지막에 쓰는 것</b>이고, 그 쓰기가 곧
 * 문을 여는 동작이다.
 *
 * <p>세우는 값은 넷이다.
 *
 * <pre>
 * seq      DB 행(확정, "N:1") + 큐들의 티켓(미확정, "N")
 * counter  max(DB 의 MAX(issue_seq),  큐들의 최대 순번)
 * free     1..counter 중 DB 에도 큐에도 없는 번호
 * pending  큐들의 티켓들
 * </pre>
 *
 * <p><b>카운터는 세지 않고 최댓값을 본다.</b> 스크립트가 {@code INCR} 을 먼저 하고 그 결과를
 * 주므로 이 키는 마지막으로 나간 번호다. 개수로 더하면 두 방향으로 깨진다. 구멍은 DB 에도 큐에도
 * 없어 모자라게 세고, {@code free} 재사용은 {@code INCR} 없이 번호가 나가 넘치게 센다. 넘치면
 * 카운터가 총량을 지나가는데 {@code free} 경로는 상한 검사를 안 지나므로,
 * <b>반드시 실패할 번호를 만들어 낸다.</b>
 */
@Slf4j
@Component
public class CouponSeqRebuilder {

    /*
     * 한 번에 Redis 로 보내는 항목 수다.
     * 만 건을 한 명령에 담으면 그동안 Redis 단일 스레드가 다른 요청을 못 받는다.
     */
    private static final int CHUNK = 500;

    private static final String COMMITTED_SUFFIX = ":1";

    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;
    private final MemberCouponSeqRepository seqRepository;
    private final CouponSeqInitializer seqInitializer;
    private final CouponSeqContributor contributor;
    private final Duration contributeWait;
    private final Duration lockTtl;

    public CouponSeqRebuilder(StringRedisTemplate redisTemplate,
                              CouponRepository couponRepository,
                              MemberCouponSeqRepository seqRepository,
                              CouponSeqInitializer seqInitializer,
                              CouponSeqContributor contributor,
                              CouponIssueProperties properties) {
        this.redisTemplate = redisTemplate;
        this.couponRepository = couponRepository;
        this.seqRepository = seqRepository;
        this.seqInitializer = seqInitializer;
        this.contributor = contributor;
        this.contributeWait = properties.rebuildContributeWait();
        // 기다림과 쓰기가 끝나기 전에 락이 풀리면 두 인스턴스가 같이 쓴다. 넉넉히 잡는다
        this.lockTtl = properties.rebuildContributeWait().multipliedBy(10);
    }

    /**
     * 이 쿠폰의 키가 사라졌으면 다시 세운다.
     *
     * <p>락을 못 잡은 인스턴스도 그냥 돌아가지 않는다. <b>자기 큐를 올리는 것이 그 인스턴스의
     * 몫</b>이고, 주도하는 쪽은 남의 큐를 알 방법이 없다.
     *
     * <p>{@code -2} 는 손실만 뜻하지 않는다. 관리자가 아직 안 연 이벤트도 같은 값을 내므로
     * <b>그 둘을 DB 로 가른다.</b>
     */
    public void rebuildIfLost(long couponId) {
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null || !coupon.isActive() || !coupon.isLimited()) {
            // 관리자가 아직 안 열었거나 선착순 쿠폰이 아니다. 카운터가 없는 것이 정상이다
            return;
        }
        if (counterExists(couponId)) {
            return;
        }

        String token = UUID.randomUUID().toString();
        if (!acquireLock(couponId, token)) {
            contributor.contribute(couponId);
            return;
        }
        try {
            lead(couponId, coupon);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("event=COUPON_SEQ_REBUILD_INTERRUPTED couponId={}", couponId);
        } finally {
            releaseLock(couponId, token);
        }
    }

    /**
     * 주도하는 인스턴스가 도는 절차다. 순서가 곧 안전장치다.
     *
     * <p>자기 큐를 먼저 올리고 남들을 기다린다. 락 키가 곧 "재건 중" 표시라, 그것을 본 인스턴스가
     * 자기 큐를 같은 자리에 올린다.
     *
     * <p><b>큐를 DB 보다 먼저 읽는다.</b> 티켓은 큐에서 DB 로만 가고 반대로는 안 간다. 큐를
     * 먼저 읽으면 그사이 넘어간 티켓이 양쪽에 다 잡히지만, DB 를 먼저 읽으면 <b>어디에도 안
     * 잡혀 구멍이 된다.</b>
     */
    private void lead(long couponId, Coupon coupon) throws InterruptedException {
        log.warn("event=COUPON_SEQ_REBUILD_STARTED couponId={} contributeWaitMillis={}",
                couponId, contributeWait.toMillis());

        contributor.contribute(couponId);
        Thread.sleep(contributeWait.toMillis());

        // 기다리는 동안 관리자가 이벤트를 다시 열었을 수 있다. 그러면 그쪽 카운터를 덮으면 안 된다
        if (counterExists(couponId)) {
            log.info("event=COUPON_SEQ_REBUILD_SKIPPED couponId={} reason=counter-appeared", couponId);
            clearContributions(couponId);
            return;
        }

        Map<Long, Integer> queued = readContributions(couponId);
        List<IssuedSeq> issued = seqRepository.findIssuedSeqs(couponId);

        int maxSeq = maxSeq(issued, queued);
        writeSeqHash(couponId, issued, queued);
        writePending(couponId, queued);
        writeFree(couponId, gaps(issued, queued, maxSeq));
        openGate(couponId, maxSeq, coupon.getIssueEndAt());
        clearContributions(couponId);

        log.warn("event=COUPON_SEQ_REBUILT couponId={} issued={} queued={} maxSeq={} freed={}",
                couponId, issued.size(), queued.size(), maxSeq,
                maxSeq - issued.size() - queued.size());
    }

    /*
     * 확정분을 먼저 넣고 큐에서 온 것은 없을 때만 넣는다.
     * 확정 표시를 미확정으로 덮으면 회수가 그 번호를 미확정으로 보고 남에게 넘겨, 이미 발급이
     * 끝난 번호가 두 번 나간다.
     */
    private void writeSeqHash(long couponId, List<IssuedSeq> issued, Map<Long, Integer> queued) {
        Map<String, String> fields = new LinkedHashMap<>(issued.size() + queued.size());
        queued.forEach((memberId, seq) -> fields.put(String.valueOf(memberId), String.valueOf(seq)));
        issued.forEach(row ->
                fields.put(String.valueOf(row.memberId()), row.issueSeq() + COMMITTED_SUFFIX));
        putAllInChunks(CouponSeqKeys.seq(couponId), fields);
    }

    /*
     * 큐의 티켓이 곧 "번호를 받았고 아직 행이 안 된 회원" 의 정의다.
     * 시각은 복원할 수 없어 지금으로 넣는다. 그만큼 회수 기준이 뒤로 밀린다.
     */
    private void writePending(long couponId, Map<Long, Integer> queued) {
        if (queued.isEmpty()) {
            return;
        }
        double now = System.currentTimeMillis();
        queued.keySet().forEach(memberId ->
                redisTemplate.opsForZSet().add(CouponSeqKeys.pending(couponId),
                        String.valueOf(memberId), now));
    }

    private void writeFree(long couponId, List<Integer> freed) {
        String key = CouponSeqKeys.free(couponId);
        for (Integer seq : freed) {
            // 점수가 번호라 스크립트의 ZPOPMIN 이 낮은 번호부터 꺼낸다
            redisTemplate.opsForZSet().add(key, String.valueOf(seq), seq);
        }
    }

    /**
     * 카운터를 세워 문을 연다. 이 메서드가 도는 순간부터 요청이 번호를 받기 시작한다.
     *
     * <p>행이 하나도 없고 큐도 비었으면 0 이다. 이벤트를 여는 {@code prepare} 가 세우는 값과
     * 같아진다.
     */
    private void openGate(long couponId, int maxSeq, LocalDateTime issueEndAt) {
        redisTemplate.opsForValue().set(CouponSeqKeys.counter(couponId), String.valueOf(maxSeq));
        seqInitializer.applyTtl(couponId, issueEndAt);
        inheritCounterTtl(couponId, CouponSeqKeys.seq(couponId), CouponSeqKeys.free(couponId),
                CouponSeqKeys.pending(couponId));
    }

    /** 나간 번호 중 가장 큰 것이다. 세지 않는 이유는 이 클래스의 주석에 있다. */
    static int maxSeq(List<IssuedSeq> issued, Map<Long, Integer> queued) {
        int max = 0;
        for (IssuedSeq row : issued) {
            max = Math.max(max, row.issueSeq());
        }
        for (Integer seq : queued.values()) {
            max = Math.max(max, seq);
        }
        return max;
    }

    /**
     * 1 부터 최대 순번까지 중 아무도 안 쥔 번호다. 이것이 곧 되살릴 재고다.
     *
     * <p>{@code MAX(issue_seq)} 가 아니라 최댓값까지 훑는다. <b>그 사이에도 나갔다가 주인을 잃은
     * 번호가 있고</b>, 최댓값이 혹시 높게 잡혀도 그 여분이 여기서 되살아난다.
     */
    static List<Integer> gaps(List<IssuedSeq> issued, Map<Long, Integer> queued, int maxSeq) {
        boolean[] taken = new boolean[maxSeq + 1];
        issued.forEach(row -> taken[row.issueSeq()] = true);
        queued.values().forEach(seq -> taken[seq] = true);

        List<Integer> freed = new ArrayList<>();
        for (int seq = 1; seq <= maxSeq; seq++) {
            if (!taken[seq]) {
                freed.add(seq);
            }
        }
        return freed;
    }

    private Map<Long, Integer> readContributions(long couponId) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(CouponSeqKeys.rebuildQueued(couponId));
        Map<Long, Integer> queued = new HashMap<>(raw.size());
        raw.forEach((member, seq) ->
                queued.put(Long.valueOf((String) member), Integer.valueOf((String) seq)));
        return queued;
    }

    private void clearContributions(long couponId) {
        redisTemplate.unlink(CouponSeqKeys.rebuildQueued(couponId));
    }

    private void putAllInChunks(String key, Map<String, String> fields) {
        Map<String, String> chunk = new HashMap<>(CHUNK);
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

    /*
     * 넷의 수명은 counter 가 들고 나머지가 따라간다.
     * 평상시에는 스크립트가 키를 만들면서 물려받는데, 재건은 카운터보다 먼저 만들어 그 경로를
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

    private boolean counterExists(long couponId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(CouponSeqKeys.counter(couponId)));
    }

    /*
     * 락 키가 곧 "재건 중" 표시다.
     * 락을 못 잡은 인스턴스는 이 키가 있는 것을 보고 자기 큐를 올린다.
     */
    private boolean acquireLock(long couponId, String token) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(CouponSeqKeys.rebuild(couponId), token, lockTtl));
    }

    /*
     * 내가 잡은 락일 때만 푼다.
     * 읽고 지우는 사이가 원자적이지 않아 아주 드물게 남의 락을 지울 수 있다. 그때도 잃는 것은
     * 없다. 뒤늦게 들어온 쪽이 카운터가 이미 선 것을 보고 그대로 돌아간다.
     */
    private void releaseLock(long couponId, String token) {
        String key = CouponSeqKeys.rebuild(couponId);
        if (token.equals(redisTemplate.opsForValue().get(key))) {
            redisTemplate.delete(key);
        }
    }
}
