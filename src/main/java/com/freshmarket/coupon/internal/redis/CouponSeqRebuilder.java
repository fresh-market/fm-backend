package com.freshmarket.coupon.internal.redis;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.freshmarket.coupon.internal.entity.Coupon;
import com.freshmarket.coupon.internal.issue.CouponIssueFlusher;
import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponSeqRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponSeqRepository.IssuedSeq;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
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
 * 주므로 이 키는 마지막으로 나간 번호다. 개수로 더하면 두 방향으로 깨진다. 결번은 DB 에도 큐에도
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

    // 다 모였는지 보는 주기다. 짧아야 다 모인 순간과 끝내는 순간의 차이가 작다
    private static final long AWAIT_POLL_MILLIS = 20;

    // 돌고 있던 배치가 끝나기를 기다리는 상한이다. CouponSeqContributor 와 같은 값을 쓴다
    private static final Duration PAUSE_TIMEOUT = Duration.ofSeconds(2);

    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;
    private final MemberCouponSeqRepository seqRepository;
    private final CouponSeqInitializer seqInitializer;
    private final CouponSeqContributor contributor;
    private final CouponIssueFlusher flusher;
    private final CouponSeqInstances instances;
    private final Duration contributeWait;
    private final Duration lockTtl;
    private final Timer duration;
    private final Timer readWrite;

    public CouponSeqRebuilder(StringRedisTemplate redisTemplate,
                              CouponRepository couponRepository,
                              MemberCouponSeqRepository seqRepository,
                              CouponSeqInitializer seqInitializer,
                              CouponSeqContributor contributor,
                              CouponIssueFlusher flusher,
                              CouponSeqInstances instances,
                              CouponIssueProperties properties,
                              MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.couponRepository = couponRepository;
        this.seqRepository = seqRepository;
        this.seqInitializer = seqInitializer;
        this.contributor = contributor;
        this.flusher = flusher;
        this.instances = instances;
        this.contributeWait = properties.rebuildContributeWait();
        // 기다림과 쓰기가 끝나기 전에 락이 풀리면 두 인스턴스가 같이 쓴다. 넉넉히 잡는다
        this.lockTtl = properties.rebuildContributeWait().multipliedBy(10);
        /*
         * 재건이 문을 닫아 둔 시간을 잰다.
         *
         * 이것이 곧 그 이벤트의 발급이 멈춘 시간이다. 그런데 여태 재는 곳이 없어서
         * "큐 수집 대기 3초 + 읽고 쓰기 427밀리초 = 약 3.4초" 라는 계산값을 실측인 것처럼
         * 써 왔다. 조기 종료를 붙인 뒤에도 줄어든 총량을 한 번도 안 쟀다.
         *
         * 감지 지연은 안 들어 있다. 카운터가 사라진 때부터가 아니라 주도자가 일을 시작한
         * 때부터이므로, 실제 정지는 이 값보다 길다.
         */
        this.duration = Timer.builder("coupon.seq.rebuild.duration")
                .description("재건이 문을 닫아 둔 시간. 주도자가 시작해 카운터를 세울 때까지다")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.readWrite = Timer.builder("coupon.seq.rebuild.readwrite")
                .description("DB 를 읽고 네 키를 세운 시간. 항목 수에 비례해 늘어난다")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /**
     * 이 쿠폰의 네 키를 DB 와 각 인스턴스의 큐에서 다시 세운다.
     *
     * <p><b>카운터의 존재를 안 본다.</b> 키가 사라진 경우와 복제가 밀린 채 승격돼 값만 뒤로 간
     * 경우를 한 절차로 다룬다. 뒤쪽은 카운터가 살아 있어, 그것을 관문으로 두면 영영 안 걸린다
     * ({@code docs/coupon/rebuild-redesign.md}).
     *
     * <p>락을 못 잡은 인스턴스도 그냥 돌아가지 않는다. <b>자기 큐를 올리는 것이 그 인스턴스의
     * 몫</b>이고, 주도하는 쪽은 남의 큐를 알 방법이 없다.
     *
     * <p>깨우는 신호는 손실만 뜻하지 않는다. 관리자가 아직 안 연 이벤트도 {@code -2} 를 내고
     * 재연결은 멀쩡할 때도 온다. <b>그 판정을 DB 로 한다.</b> 다만 주도하려는 쪽만 한다.
     */
    public void rebuild(long couponId) {
        /*
         * 이미 재건이 돌고 있으면 DB 를 안 보고 기여만 하고 돌아간다.
         *
         * 락이 있다는 것은 주도자가 DB 로 "진짜 손실" 이라고 이미 판정했다는 뜻이다. 그 판정을
         * 여기서 또 할 이유가 없다.
         *
         * 이 한 줄이 필요한 이유는 DB 장애가 겹칠 때다. 기여는 Redis 와 자기 큐만 건드리는데,
         * 아래 findById 뒤에 두면 DB 에 못 닿는 인스턴스가 거기서 터져 자기 큐를 영영 못 올린다.
         * 큐는 Redis 가 죽어도 살아 있는 유일한 미확정 기록이라, 안 올리면 재건이 그 번호들을
         * 아무도 안 쥔 것으로 보고 남에게 다시 내준다. 보호가 가장 필요한 상황에서 그 보호가
         * 사라진다 (docs/coupon/rebuild-measurement-2026-09-21b.md 3장).
         */
        if (rebuildInProgress(couponId)) {
            contributor.contribute(couponId);
            return;
        }

        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null || !coupon.isActive() || !coupon.isLimited()) {
            // 관리자가 아직 안 열었거나 선착순 쿠폰이 아니다. 카운터가 없는 것이 정상이다
            return;
        }

        RebuildLock lock = RebuildLock.start();
        if (!acquireLock(couponId, lock)) {
            // 위 확인과 여기 사이에 남이 잡았다. 그쪽이 주도자이므로 올리기만 한다
            contributor.contribute(couponId);
            return;
        }
        try {
            lead(couponId, coupon);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("event=COUPON_SEQ_REBUILD_INTERRUPTED couponId={}", couponId);
        } finally {
            releaseLock(couponId, lock);
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
     * 잡혀 결번이 된다.</b>
     */
    private void lead(long couponId, Coupon coupon) throws InterruptedException {
        log.warn("event=COUPON_SEQ_REBUILD_STARTED couponId={} contributeWaitMillis={}",
                couponId, contributeWait.toMillis());

        long startedAt = System.nanoTime();

        /*
         * 멈춤을 올리는 동안만이 아니라 쓰기가 끝날 때까지 끌고 간다.
         *
         * 락이 발급을 막고 있어 새 티켓이 안 들어온다. 그래서 멈춰 둬도 큐가 자라지 않는다.
         * 그 대신 DB 를 읽은 뒤 네 키를 쓰기까지의 사이에 커밋된 티켓이 미확정으로 남는 창이
         * 없어진다 (docs/coupon/rebuild-redesign.md 7장).
         */
        if (!flusher.pause(PAUSE_TIMEOUT)) {
            log.warn("event=COUPON_SEQ_REBUILD_SKIPPED couponId={} reason=pause-timeout", couponId);
            return;
        }
        try {
            leadWhilePaused(couponId, coupon, startedAt);
        } finally {
            flusher.resume();
            clearContributions(couponId);
        }
    }

    private void leadWhilePaused(long couponId, Coupon coupon, long startedAt) throws InterruptedException {
        contributor.uploadWhilePaused(couponId);
        long contributedAt = System.nanoTime();
        awaitContributions(couponId);
        long awaitedAt = System.nanoTime();

        /*
         * 기다리는 동안 관리자가 이벤트를 다시 준비했을 수 있다. 그러면 그쪽 카운터를 덮으면 안 된다.
         *
         * 승격 경로에서는 카운터가 원래 살아 있으므로 존재만으로는 못 가른다. 준비 단계가 세우는
         * 값이 0 이라 그것으로 가른다.
         */
        if (reprepared(couponId)) {
            log.info("event=COUPON_SEQ_REBUILD_SKIPPED couponId={} reason=reprepared", couponId);
            return;
        }

        Map<Long, Integer> queued = readContributions(couponId);
        List<IssuedSeq> issued = seqRepository.findIssuedSeqs(couponId);

        int maxSeq = maxSeq(issued, queued);
        clearDerived(couponId, issued);
        writeSeqHash(couponId, issued, queued);
        addPending(couponId, queued);
        writeFree(couponId, gaps(issued, queued, maxSeq));
        openGate(couponId, maxSeq, coupon.getIssueEndAt());

        /*
         * 문이 열리는 자리가 여기다. 뒷정리는 그 뒤이므로 재는 끝점도 여기다.
         * clearContributions 까지 재면 이미 발급이 재개된 시간을 정지로 세게 된다.
         */
        long openedAt = System.nanoTime();
        duration.record(openedAt - startedAt, TimeUnit.NANOSECONDS);
        readWrite.record(openedAt - awaitedAt, TimeUnit.NANOSECONDS);

        log.warn("event=COUPON_SEQ_REBUILT couponId={} issued={} queued={} maxSeq={} freed={}"
                        + " rebuildMillis={} contributeMillis={} waitMillis={} readWriteMillis={}",
                couponId, issued.size(), queued.size(), maxSeq,
                maxSeq - issued.size() - queued.size(),
                TimeUnit.NANOSECONDS.toMillis(openedAt - startedAt),
                TimeUnit.NANOSECONDS.toMillis(contributedAt - startedAt),
                TimeUnit.NANOSECONDS.toMillis(awaitedAt - contributedAt),
                TimeUnit.NANOSECONDS.toMillis(openedAt - awaitedAt));
    }

    /**
     * 남들이 자기 큐를 다 올리기를 기다린다.
     *
     * <p><b>예전에는 여기서 고정으로 잤다.</b> 주도자가 몇 대를 기다려야 하는지 몰라 시간으로
     * 때웠고, 그 시간이 재건 정지의 대부분이었다. 명부가 생긴 뒤로는 <b>다 모이면 그 자리에서
     * 끝낸다.</b> 정해진 시간은 바닥이 아니라 천장이 된다.
     *
     * <p><b>천장은 없앨 수 없다.</b> 죽었거나 먹통인 인스턴스는 영영 표시를 안 남긴다.
     *
     * <p><b>명부를 못 읽으면 기다림을 안 줄인다.</b> 살아 있는 수를 0 으로 받았을 때 "다 모였다"
     * 로 읽으면 아무도 안 기다린 채 키를 세우게 된다. 모르는 채로 일찍 끝내는 것이 이 기능에서
     * 가장 나쁜 결과다.
     */
    private void awaitContributions(long couponId) throws InterruptedException {
        long deadline = System.nanoTime() + contributeWait.toNanos();
        while (System.nanoTime() < deadline) {
            int live = instances.live();
            if (live > 0 && doneCount(couponId) >= live) {
                log.info("event=COUPON_SEQ_CONTRIBUTIONS_COMPLETE couponId={} instances={} waitedMillis={}",
                        couponId, live,
                        contributeWait.toMillis() - TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                return;
            }
            Thread.sleep(AWAIT_POLL_MILLIS);
        }
        log.warn("event=COUPON_SEQ_CONTRIBUTIONS_TIMEOUT couponId={} instances={} done={} waitedMillis={}",
                couponId, instances.live(), doneCount(couponId), contributeWait.toMillis());
    }

    private int doneCount(long couponId) {
        Long done = redisTemplate.opsForSet().size(CouponSeqKeys.rebuildDone(couponId));
        return done == null ? 0 : done.intValue();
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
    /**
     * 다시 쓸 키를 먼저 비운다. {@code pending} 만 통째로 안 지운다.
     *
     * <p><b>{@code seq} 와 {@code free} 는 비우고 쓴다.</b> 승격 경로에서는 옛 내용이 살아
     * 있는데, 덮어쓰기만 하면 어느 쪽에도 안 잡힌 항목이 그대로 남는다. {@code free} 에 남은
     * 옛 번호는 주인이 정해진 뒤에도 다시 나가고, {@code seq} 에 남은 옛 매핑은 그 회원을 이미
     * 남에게 간 번호로 되돌린다.
     *
     * <p><b>{@code pending} 은 DB 에 행이 있는 회원만 뺀다.</b> 살아남은 항목은 참값이고
     * 점수도 그 회원이 실제로 번호를 받은 시각이다. 통째로 다시 쓰면 점수가 재건 시각이 되어
     * 회수가 그만큼 뒤로 밀린다. 그리고 올라온 것이 비면 이 키가 텅 비어, 소진 근처에서
     * 스크립트가 최종 소진으로 판정해 재시도를 막는다.
     *
     * <p>비어 있는 순간을 아무도 못 읽는다. 락이 순번 확보를 막고 있다.
     */
    private void clearDerived(long couponId, List<IssuedSeq> issued) {
        redisTemplate.unlink(CouponSeqKeys.seq(couponId));
        redisTemplate.unlink(CouponSeqKeys.free(couponId));
        removeSettledFromPending(couponId, issued);
    }

    private void removeSettledFromPending(long couponId, List<IssuedSeq> issued) {
        String key = CouponSeqKeys.pending(couponId);
        List<Object> chunk = new ArrayList<>(CHUNK);
        for (IssuedSeq row : issued) {
            chunk.add(String.valueOf(row.memberId()));
            if (chunk.size() == CHUNK) {
                redisTemplate.opsForZSet().remove(key, chunk.toArray());
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            redisTemplate.opsForZSet().remove(key, chunk.toArray());
        }
    }

    /**
     * 올라온 회원을 {@code pending} 에 더한다. 이미 있으면 점수를 안 건드린다.
     *
     * <p>{@code ZADD NX} 다. 살아남은 항목의 점수는 그 회원이 실제로 번호를 받은 시각이라
     * 재건 시각으로 덮으면 회수가 뒤로 밀린다.
     *
     * <p>새로 넣는 항목에는 재건 시각을 준다. <b>그 회원의 원래 시각은 복원할 수 없다.</b>
     * 사라진 Redis 에만 있었다.
     */
    private void addPending(long couponId, Map<Long, Integer> queued) {
        String key = CouponSeqKeys.pending(couponId);
        double now = System.currentTimeMillis();
        for (Long memberId : queued.keySet()) {
            redisTemplate.opsForZSet().addIfAbsent(key, String.valueOf(memberId), now);
        }
    }

    private void writeFree(long couponId, List<Integer> freed) {
        // 점수가 번호라 스크립트의 ZPOPMIN 이 낮은 번호부터 꺼낸다
        Set<TypedTuple<String>> numbers = freed.stream()
                .map(seq -> TypedTuple.of(String.valueOf(seq), (double) seq))
                .collect(Collectors.toSet());
        addInChunks(CouponSeqKeys.free(couponId), numbers);
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
        // 완료 표시도 함께 지운다. 안 지우면 다음 재건이 지난 회차의 수를 보고 즉시 끝낸다
        redisTemplate.unlink(CouponSeqKeys.rebuildDone(couponId));
    }

    /*
     * 항목마다 한 번씩 치면 만 건이 만 번의 왕복이 된다.
     * 재건은 발급이 멈춰 있는 동안 도는 일이라 그 시간이 곧 장애 시간이다.
     */
    private void addInChunks(String key, Set<TypedTuple<String>> values) {
        if (values.isEmpty()) {
            return;
        }
        Set<TypedTuple<String>> chunk = new HashSet<>(CHUNK);
        for (TypedTuple<String> value : values) {
            chunk.add(value);
            if (chunk.size() == CHUNK) {
                redisTemplate.opsForZSet().add(key, chunk);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            redisTemplate.opsForZSet().add(key, chunk);
        }
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

    /**
     * 기다리는 동안 관리자가 이벤트를 다시 준비했는지 본다.
     *
     * <p>준비 단계({@code CouponSeqInitializer})가 카운터를 0 으로 세운다. 이 재건이 세울 값은
     * 나간 번호의 최댓값이라 0 이 될 수 없다. <b>0 이 보이면 그쪽이 새로 연 것이므로 덮지 않는다.</b>
     *
     * <p>키의 존재로는 못 가른다. 승격 경로에서는 카운터가 원래 살아 있다.
     */
    private boolean reprepared(long couponId) {
        String raw = redisTemplate.opsForValue().get(CouponSeqKeys.counter(couponId));
        return "0".equals(raw);
    }

    // 락 키가 곧 "누가 이미 주도하고 있다" 는 표시다
    private boolean rebuildInProgress(long couponId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(CouponSeqKeys.rebuild(couponId)));
    }

    /*
     * 락 키가 곧 "재건 중" 표시다.
     * 락을 못 잡은 인스턴스는 이 키가 있는 것을 보고 자기 큐를 올린다.
     */
    private boolean acquireLock(long couponId, RebuildLock lock) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(CouponSeqKeys.rebuild(couponId), lock.value(), lockTtl));
    }

    /*
     * 내가 잡은 락일 때만 푼다.
     * 읽고 지우는 사이가 원자적이지 않아 아주 드물게 남의 락을 지울 수 있다. 그때도 잃는 것은
     * 없다. 뒤늦게 들어온 쪽이 카운터가 이미 선 것을 보고 그대로 돌아간다.
     */
    private void releaseLock(long couponId, RebuildLock lock) {
        String key = CouponSeqKeys.rebuild(couponId);
        if (lock.value().equals(redisTemplate.opsForValue().get(key))) {
            redisTemplate.delete(key);
        }
    }
}
