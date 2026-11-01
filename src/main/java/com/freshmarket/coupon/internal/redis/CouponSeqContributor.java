package com.freshmarket.coupon.internal.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.internal.issue.CouponIssueQueue;
import com.freshmarket.coupon.internal.issue.IssueTicket;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 재건 때 이 인스턴스가 쥔 티켓을 {@code seq} 와 {@code pending} 에 되살린다.
 *
 * <p><b>큐는 Redis 가 죽어도 살아 있는 유일한 미확정 기록이다.</b> DB 에는 커밋된 것만 있고,
 * 큐에는 번호를 받았지만 아직 행이 안 된 것이 있다. 되살리지 않으면 두 가지가 깨진다. 그 회원이
 * 재시도할 때 자기 번호를 못 찾아 번호를 하나 더 태우고, {@code free} 를 쓰는 쪽이 그 번호를
 * "아무도 안 쥔 것" 으로 보고 남에게 내준다.
 *
 * <p><b>아무에게도 보고하지 않는다.</b> 예전에는 자기 큐를 {@code rebuild:queued} 에 올리고
 * {@code rebuild:done} 에 이름을 남겼고, 주도자가 그 수를 명부의 수와 견줘 기다림을 끝냈다.
 * 그 세기가 실패하는 경우가 회차마다 새로 나왔다
 * ({@code docs/coupon/rebuild-v2/design.md} 7.1). <b>이제 쥔 번호의 기록은
 * {@code seq} 해시 하나이고, {@code free} 를 쓰는 스크립트가 그것을 직접 본다.</b>
 *
 * <p><b>플러시를 멈추지 않는다.</b> 멈춤이 필요했던 까닭은 훑는 사이에 티켓이 DB 로 내려가면
 * 확정 표시({@code "N:1"})를 미확정({@code "N"})으로 덮기 때문이었다. 쓰기를 "없을 때만" 으로
 * 바꾸면 그 덮어쓰기가 구조적으로 안 생기므로 멈출 이유가 사라진다 (같은 문서 5.2).
 */
@Slf4j
@Component
public class CouponSeqContributor {

    /*
     * 한 번에 보내는 항목 수다.
     * 만 건을 한 파이프라인에 담으면 그동안 Redis 단일 스레드가 다른 요청을 못 받는다.
     */
    private static final int CHUNK = 500;

    private final StringRedisTemplate redisTemplate;
    private final CouponIssueQueue queue;
    private final Timer write;

    public CouponSeqContributor(CouponSeqRebuildRedis rebuildRedis,
                                CouponIssueQueue queue,
                                MeterRegistry registry) {
        this.redisTemplate = rebuildRedis.template();
        this.queue = queue;
        this.write = Timer.builder("coupon.seq.rebuild.restore")
                .description("이 인스턴스가 자기 큐를 훑어 seq 와 pending 에 되살린 시간")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /**
     * 이 쿠폰의 내 티켓을 {@code seq} 와 {@code pending} 에 되살린다.
     *
     * <p><b>둘 다 "없을 때만" 쓴다.</b> 그것이 멈춤을 없앤 근거다.
     *
     * <pre>
     * seq      HSETNX   있는 값을 안 건드린다. 확정 표시를 미확정으로 덮으면 회수가 끝난 번호를 뺏는다
     * pending  ZADD NX  있는 점수를 안 건드린다. 재건 시각으로 덮으면 회수가 그만큼 뒤로 밀린다
     * </pre>
     *
     * <p><b>겹쳐 들어와도 안전하다.</b> 훑는 사이에 그 티켓이 커밋되면 확정 쪽이 {@code "N:1"} 을
     * 쓰는데, 내 쓰기가 먼저면 그쪽이 덮고 내 쓰기가 나중이면 {@code NX} 가 아무 일도 안 한다.
     * {@code pending} 에 뒤늦게 들어간 항목도 해롭지 않다. 회수 스크립트가 확정 표시가 붙은
     * 번호는 {@code pending} 에서만 빼고 되살리지 않는다.
     */
    public void restore(long couponId) {
        long startedAt = System.nanoTime();
        List<IssueTicket> mine = mineFor(couponId);
        if (mine.isEmpty()) {
            log.info("event=COUPON_SEQ_RESTORED couponId={} held=0", couponId);
            return;
        }

        for (int from = 0; from < mine.size(); from += CHUNK) {
            writeChunk(couponId, mine.subList(from, Math.min(from + CHUNK, mine.size())));
        }
        write.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);

        log.warn("event=COUPON_SEQ_RESTORED couponId={} held={} writeMillis={}",
                couponId, mine.size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    /*
     * 한 덩이를 왕복 하나로 보낸다.
     *
     * 티켓마다 치면 만 건이 만 번의 왕복이다. 그 시간 동안 free 를 쓰는 쪽이 이 인스턴스의
     * 번호를 아직 못 보므로, 왕복 수가 곧 그 창의 길이다.
     */
    private void writeChunk(long couponId, List<IssueTicket> chunk) {
        byte[] seqKey = CouponSeqKeys.seq(couponId).getBytes(StandardCharsets.UTF_8);
        byte[] pendingKey = CouponSeqKeys.pending(couponId).getBytes(StandardCharsets.UTF_8);
        double now = System.currentTimeMillis();
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (IssueTicket ticket : chunk) {
                byte[] member = String.valueOf(ticket.memberId()).getBytes(StandardCharsets.UTF_8);
                connection.hashCommands().hSetNX(seqKey, member,
                        String.valueOf(ticket.issueSeq()).getBytes(StandardCharsets.UTF_8));
                /*
                 * 점수를 복원할 수 없어 지금으로 넣는다. 그만큼 회수 기준이 뒤로 밀린다.
                 * 그 회원의 원래 시각은 사라진 Redis 에만 있었다.
                 */
                connection.zSetCommands().zAdd(pendingKey, now, member,
                        RedisZSetCommands.ZAddArgs.ifNotExists());
            }
            return null;
        });
    }

    /**
     * 이 쿠폰에 걸린 내 티켓을 모은다.
     *
     * <p>큐를 얼리지 않고 훑는다. 훑는 사이에 빠진 티켓은 DB 로 내려간 것이고, 그쪽이 쓰는
     * 확정 표시가 이 메서드의 결과보다 정확하다.
     */
    private List<IssueTicket> mineFor(long couponId) {
        List<IssueTicket> mine = new ArrayList<>();
        for (IssueTicket ticket : queue.snapshot()) {
            if (ticket.couponId() == couponId) {
                mine.add(ticket);
            }
        }
        return mine;
    }
}
