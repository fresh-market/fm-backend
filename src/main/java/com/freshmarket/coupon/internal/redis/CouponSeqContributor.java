package com.freshmarket.coupon.internal.redis;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.internal.issue.CouponIssueFlusher;
import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.issue.CouponIssueQueue;
import com.freshmarket.coupon.internal.issue.IssueTicket;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 재건이 도는 동안 이 인스턴스가 쥔 순번을 올린다. 주도하든 안 하든 모든 인스턴스가 부른다
 * ({@code docs/coupon/coupon.md} 10장).
 *
 * <p><b>큐는 Redis 가 죽어도 살아 있는 유일한 미확정 기록이다.</b> DB 에는 커밋된 것만 있고,
 * 큐에는 번호를 받았지만 아직 행이 안 된 것이 있다. 이것을 안 올리면 재건이 그 번호들을 아무도
 * 안 쥔 것으로 보고 <b>남에게 다시 내준다.</b>
 *
 * <p><b>남의 큐를 알 필요가 없다.</b> 각자 자기 것만 같은 해시에 올린다. 회원 하나의 티켓은 한
 * 인스턴스에만 있으므로 겹치지 않고, 순서도 상관없다.
 */
@Slf4j
@Component
public class CouponSeqContributor {

    /*
     * 큐를 얼리기를 기다리는 시한이다.
     * 배치 하나가 끝나기를 기다리는 것뿐이라 짧다. 이 시간을 넘기면 그 배치가 막힌 것이므로
     * 훑기를 포기한다. 흔들리는 목록으로 순번의 주인을 정하느니 안 올리는 편이 낫다.
     */
    private static final Duration PAUSE_TIMEOUT = Duration.ofSeconds(2);

    private static final String CONTRIBUTE_LAG = "coupon.seq.rebuild.contribute.lag";
    private static final String CONTRIBUTE_PAUSE = "coupon.seq.rebuild.contribute.pause";
    private static final String CONTRIBUTE_WRITE = "coupon.seq.rebuild.contribute.write";

    private final StringRedisTemplate redisTemplate;
    private final CouponIssueQueue queue;
    private final CouponIssueFlusher flusher;
    private final CouponSeqInstances instances;
    private final Duration queuedTtl;
    private final Timer lag;
    private final Timer pauseWait;
    private final Timer write;

    public CouponSeqContributor(StringRedisTemplate redisTemplate,
                                CouponIssueQueue queue,
                                CouponIssueFlusher flusher,
                                CouponSeqInstances instances,
                                CouponIssueProperties properties,
                                MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.queue = queue;
        this.flusher = flusher;
        this.instances = instances;
        // 재건 락과 같은 수명이다. 재건이 끝나기 전에 사라지면 주도자가 이 인스턴스의 큐를 못 읽는다
        this.queuedTtl = properties.rebuildContributeWait().multipliedBy(10);
        this.lag = Timer.builder(CONTRIBUTE_LAG)
                .description("재건이 시작된 뒤 이 인스턴스가 자기 큐를 다 올리기까지 걸린 시간")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        /*
         * lag 을 셋으로 가르려고 둘을 더 둔다.
         *
         * lag 은 "락이 선 때부터 다 올린 때까지" 라 그 안에 세 가지가 섞여 있다. 이 메서드에
         * 들어오기까지 기다린 시간, pause 가 돌던 배치를 기다린 시간, 훑고 쓴 시간이다.
         * 셋의 처방이 서로 다르므로 총합만 보고 rebuild-contribute-wait 를 건드리면 안 된다.
         *
         * 들어오기까지 기다린 시간은 따로 안 잰다. lag 에서 아래 둘을 빼면 그것이다.
         */
        this.pauseWait = Timer.builder(CONTRIBUTE_PAUSE)
                .description("기여가 돌던 배치가 끝나기를 기다린 시간")
                .register(registry);
        this.write = Timer.builder(CONTRIBUTE_WRITE)
                .description("기여가 자기 큐를 훑어 Redis 에 쓴 시간")
                .register(registry);
    }

    /**
     * 이 인스턴스의 큐에서 이 쿠폰의 티켓을 골라 올린다.
     *
     * <p>플러시를 먼저 멈춘다. 안 멈추면 훑는 사이에 티켓이 DB 로 내려가고, 재건이 그것을
     * 미확정으로 덮어 회수가 확정된 번호를 남에게 넘긴다.
     */
    public void contribute(long couponId) {
        long enteredAt = System.nanoTime();
        if (!flusher.pause(PAUSE_TIMEOUT)) {
            log.warn("event=COUPON_SEQ_CONTRIBUTE_SKIPPED couponId={} reason=pause-timeout", couponId);
            return;
        }
        long pausedAt = System.nanoTime();
        pauseWait.record(pausedAt - enteredAt, TimeUnit.NANOSECONDS);
        try {
            Map<String, String> mine = mineFor(couponId);
            if (mine.isEmpty()) {
                /*
                 * 올릴 것이 없어도 늦은 정도는 잰다.
                 * 안 재면 "기여가 안 늦었다" 와 "올릴 것이 없었다" 가 지표에서 같아진다.
                 * 2026-09-21 회차가 전부 후자였는데 표본이 0건이라 그 사실을 지표로는 못 봤다.
                 */
                markDone(couponId);
                recordLag(couponId, 0, enteredAt, pausedAt);
                return;
            }
            String key = CouponSeqKeys.rebuildQueued(couponId);
            redisTemplate.opsForHash().putAll(key, mine);
            /*
             * 이 키는 다른 넷과 달리 counter 의 만료를 물려받을 자리가 없다.
             * 지우는 것이 주도자의 정리 한 번뿐이라, 그 정리와 락 해제 사이에 올린 기여는
             * 아무도 안 지운다. 그래서 여기서 직접 시한을 건다.
             */
            redisTemplate.expire(key, queuedTtl);
            markDone(couponId);
            recordLag(couponId, mine.size(), enteredAt, pausedAt);
        } finally {
            flusher.resume();
        }
    }

    /**
     * 주도자가 락을 세운 뒤 이 인스턴스가 다 올리기까지 걸린 시간을 남긴다.
     *
     * <p><b>이 값이 {@code rebuild-contribute-wait} 를 좁히는 근거다.</b> 지금 3초는 GC 정지와
     * 배치 하나를 덮을 만큼으로 고른 값이지 실측으로 좁힌 값이 아니다. 회차마다 가장 늦은 기여가
     * 몇 밀리초였는지 쌓이면 그 분포를 보고 줄일 수 있다.
     *
     * <p>재는 데 실패해도 기여는 이미 끝났다. 그래서 여기서 예외를 밖으로 안 보낸다.
     */
    private void recordLag(long couponId, int size, long enteredAt, long pausedAt) {
        long writeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pausedAt);
        long pauseMillis = TimeUnit.NANOSECONDS.toMillis(pausedAt - enteredAt);
        write.record(System.nanoTime() - pausedAt, TimeUnit.NANOSECONDS);

        OptionalLong startedAt = OptionalLong.empty();
        try {
            startedAt = RebuildLock.startedAtOf(
                    redisTemplate.opsForValue().get(CouponSeqKeys.rebuild(couponId)));
        } catch (RuntimeException e) {
            log.debug("event=COUPON_SEQ_CONTRIBUTE_LAG_UNKNOWN couponId={}", couponId, e);
        }
        if (startedAt.isEmpty()) {
            log.warn("event=COUPON_SEQ_CONTRIBUTED couponId={} size={} pauseMillis={} writeMillis={}",
                    couponId, size, pauseMillis, writeMillis);
            return;
        }
        long lagMillis = Math.max(0, System.currentTimeMillis() - startedAt.getAsLong());
        lag.record(lagMillis, TimeUnit.MILLISECONDS);
        /*
         * 셋을 한 줄에 같이 낸다.
         * lag 에서 pause 와 write 를 빼면 이 메서드에 들어오기까지 기다린 시간이고, 그 값이
         * 크면 고칠 자리가 기여가 아니라 그 앞이다. 지표를 못 보는 자리에서도 읽히게 한다.
         */
        log.warn("event=COUPON_SEQ_CONTRIBUTED couponId={} size={} lagMillis={} pauseMillis={} writeMillis={} waitMillis={}",
                couponId, size, lagMillis, pauseMillis, writeMillis,
                Math.max(0, lagMillis - pauseMillis - writeMillis));
    }

    /**
     * 다 올렸다고 이름을 남긴다. 주도자는 이 수가 명부의 수에 닿으면 기다림을 끝낸다.
     *
     * <p><b>올릴 것이 없었을 때도 남긴다.</b> "아직 안 올렸다" 와 "올릴 것이 없었다" 를 주도자가
     * 구분하지 못하면, 올릴 것이 없는 인스턴스 하나 때문에 정해진 시간을 끝까지 기다린다.
     *
     * <p>이 표시를 올리기보다 먼저 하면 안 된다. 주도자가 아직 안 들어온 큐를 다 온 것으로 보고
     * 진행한다. <b>반드시 쓰기가 끝난 뒤다.</b>
     */
    private void markDone(long couponId) {
        redisTemplate.opsForSet().add(CouponSeqKeys.rebuildDone(couponId), instances.id());
        redisTemplate.expire(CouponSeqKeys.rebuildDone(couponId), queuedTtl);
    }

    private Map<String, String> mineFor(long couponId) {
        List<IssueTicket> snapshot = queue.snapshot();
        Map<String, String> mine = new HashMap<>();
        for (IssueTicket ticket : snapshot) {
            if (ticket.couponId() == couponId) {
                mine.put(String.valueOf(ticket.memberId()), String.valueOf(ticket.issueSeq()));
            }
        }
        return mine;
    }
}
