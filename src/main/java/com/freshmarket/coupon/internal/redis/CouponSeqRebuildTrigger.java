package com.freshmarket.coupon.internal.redis;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import com.freshmarket.coupon.internal.issue.CouponSeqRebuildSignal;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 요청이 순번을 못 받았을 때 재건을 한 번 띄운다. 감지를 요청 경로에 두고 일은 뒤 스레드가 한다.
 *
 * <p><b>요청 스레드가 재건을 직접 하면 안 된다.</b> 이벤트가 열리는 순간에는 수만 개가 동시에
 * 같은 응답을 받으므로, 그 자리에서 일을 시키면 수만 개가 같은 재건을 하려 든다. 여기서는
 * 후보를 집합에 넣기만 하고 돌아간다.
 *
 * <p><b>주기 실행이 아니다.</b> {@code add} 가 거짓을 주는 것이 곧 "남이 이미 시작했다" 라서,
 * 첫 요청 하나만 작업을 띄우고 나머지는 그대로 돌아간다. 평소에는 도는 것이 없다.
 *
 * <p>재시도가 따로 필요 없다. 재건이 실패하면 집합에서 빠지고, 이벤트가 도는 동안에는 다음
 * 요청이 또 같은 응답을 받아 다시 띄운다.
 */
@Slf4j
@Component
public class CouponSeqRebuildTrigger implements CouponSeqRebuildSignal {

    private static final String COUNTER_BEHIND = "coupon.seq.counter.behind";

    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();

    /*
     * 이 인스턴스가 이 쿠폰에서 지금까지 본 가장 큰 순번이다.
     * 카운터가 뒤로 갔는지를 이것과 견줘서 안다. 카운터가 사라진 동안에는 지운다.
     */
    private final Map<Long, Integer> highWater = new ConcurrentHashMap<>();

    /*
     * 스레드 하나로 족하다. 재건은 이벤트당 많아야 몇 번 도는 일이고, 둘이 붙어 봐야 같은 락을
     * 다툰다. 데몬으로 두어 이 스레드가 종료를 붙잡지 않게 한다.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coupon-seq-rebuild");
        thread.setDaemon(true);
        return thread;
    });

    private final CouponSeqRebuilder rebuilder;
    private final StringRedisTemplate redisTemplate;
    private final CouponSeqInstances instances;
    private final DistributionSummary behind;

    public CouponSeqRebuildTrigger(CouponSeqRebuilder rebuilder,
                                   StringRedisTemplate redisTemplate,
                                   CouponSeqInstances instances,
                                   MeterRegistry registry) {
        this.rebuilder = rebuilder;
        this.redisTemplate = redisTemplate;
        this.instances = instances;
        this.behind = DistributionSummary.builder(COUNTER_BEHIND)
                .description("카운터가 이미 나간 순번보다 얼마나 뒤처졌나. 정상 운영에서는 표본이 0건이다")
                .register(registry);
    }

    /**
     * 플러시 스레드가 배치를 쓴 뒤 부른다. 재건 중이면 이 인스턴스도 자기 큐를 올려야 한다.
     * 같은 자리에서 카운터가 뒤처지지 않았는지도 잰다.
     *
     * <p>확인이 키 두 개 읽기이고 배치당 한 번이라 값이 싸다. 창 20밀리초 기준으로 초당 100번이다.
     */
    @Override
    public void checkAfterFlush(long couponId, int maxIssuedSeq) {
        try {
            /*
             * 명부를 여기서 갱신한다. 이 자리가 맞는 이유는 플러시 루프가 큐에 티켓이 있을 때만
             * 여기까지 오기 때문이다. 갱신이 신선한 것과 큐를 쥐고 있는 것이 같은 뜻이 된다.
             */
            instances.refresh();
            if (Boolean.TRUE.equals(redisTemplate.hasKey(CouponSeqKeys.rebuild(couponId)))) {
                suspect(couponId);
            }
            measureBehind(couponId, maxIssuedSeq);
        } catch (DataAccessException e) {
            /*
             * 이 확인이 실패해도 방금 쓴 배치는 이미 성공한 것이다.
             *
             * 삼키지 않으면 플러시 루프의 바깥 catch 가 그 배치를 실패로 처리한다. 사용자 응답은
             * 이미 완료돼 안 바뀌지만, DB 에 잘 쓴 배치마다 오류 로그가 찍혀 정말 실패한 배치를
             * 가린다. Redis 가 죽어도 큐에 든 발급은 끝까지 간다는 성질(coupon.md 8장)이 여기서
             * 깨지면 안 된다.
             */
            log.debug("event=COUPON_SEQ_REBUILD_CHECK_FAILED couponId={}", couponId, e);
        }
    }

    /**
     * 카운터가 이미 나간 순번보다 뒤처졌는지 잰다.
     *
     * <p><b>이 식은 깨질 수 없다.</b> 번호는 카운터를 {@code INCR} 한 뒤에야 나가고 카운터는
     * 정상적으로 줄지 않으므로, 카운터는 나간 어느 번호보다도 크거나 같다.
     *
     * <pre>
     * counter &gt;= 이 배치의 최대 순번      재건이 살아 있는 티켓보다 낮게 문을 열면 깨진다
     * counter &gt;= 여태 본 최대 순번        복제가 밀린 채 승격돼 카운터가 뒤로 가면 깨진다
     * </pre>
     *
     * <p><b>재기만 하고 재건은 안 띄운다.</b> 재건은 그 이벤트의 플러시를 3초 넘게 멈추므로
     * 오탐이 곧 장애다. 정상 운영에서 이 표본이 0건이라는 것을 회차로 확인한 뒤에 붙인다
     * ({@code docs/coupon/rebuild-measurement-2026-09-21b.md} 5장).
     *
     * <p><b>카운터가 없으면 기준을 버린다.</b> 재건 중이거나 이벤트가 닫힌 것이라 견줄 대상이
     * 아니다. 다만 이 때문에 이벤트를 발급 이력째 다시 준비하면 그 첫 배치가 한 번 걸릴 수 있다.
     */
    private void measureBehind(long couponId, int maxIssuedSeq) {
        String raw = redisTemplate.opsForValue().get(CouponSeqKeys.counter(couponId));
        if (raw == null) {
            highWater.remove(couponId);
            return;
        }
        int mark = highWater.merge(couponId, maxIssuedSeq, Math::max);
        long counter;
        try {
            counter = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("event=COUPON_SEQ_COUNTER_UNREADABLE couponId={} value={}", couponId, raw);
            return;
        }
        if (counter >= mark) {
            return;
        }
        long gap = mark - counter;
        behind.record(gap);
        log.error("event=COUPON_SEQ_COUNTER_BEHIND couponId={} counter={} seen={} gap={}",
                couponId, counter, mark, gap);
    }

    /**
     * 요청 스레드와 플러시 스레드가 부른다. 하는 일이 집합에 넣는 것뿐이라 지는 값이 거의 없다.
     *
     * <p><b>부르는 자리가 둘인 이유가 있다.</b> 요청 경로만 두면 ALB 가 요청을 안 보내는
     * 인스턴스는 손실을 영영 모른다. 그런데 티켓을 쥔 인스턴스는 그것을 쓰느라 반드시 Redis 를
     * 만지므로, 플러시 쪽에서 알아챌 수 있다.
     *
     * <p>이 호출은 손실을 단정하지 않는다. 관리자가 아직 안 연 이벤트도 같은 자리로 오므로,
     * 그 둘을 가르는 것은 {@link CouponSeqRebuilder} 가 DB 를 보고 한다.
     */
    public void suspect(long couponId) {
        if (!inProgress.add(couponId)) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    rebuilder.rebuildIfLost(couponId);
                } catch (RuntimeException e) {
                    // 삼키면 안 되지만 이 스레드를 죽여서도 안 된다. 다음 요청이 다시 띄운다
                    log.error("event=COUPON_SEQ_REBUILD_FAILED couponId={}", couponId, e);
                } finally {
                    inProgress.remove(couponId);
                }
            });
        } catch (RejectedExecutionException e) {
            // 종료 중이다. 표시를 남기면 다음 기동에서도 안 뜨므로 지운다
            inProgress.remove(couponId);
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
