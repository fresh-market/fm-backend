package com.freshmarket.coupon.internal.redis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponConsistencyRepository;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 주인을 잃은 번호를 재고로 되돌린다. 설계 근거는
 * {@code docs/coupon/free-sweep-2026-10-12.md} 에 있다.
 *
 * <p><b>이것이 없으면 승격 회차에서 재고가 덜 나간다.</b> 2026-10-12 U-3 에서 결번 42,
 * U-5 에서 20 이 났다. 그 번호들은 승격의 복제 지연 창에서 나갔는데 티켓이 큐에 못 들어가
 * {@code member_coupon} 에도 큐에도 {@code pending} 에도 없다. <b>카운터만 그 번호가 나갔다는
 * 것을 안다.</b>
 *
 * <p><b>옛 재건은 이것을 {@code 1..최댓값} 에서 DB 와 모든 큐를 빼서 세웠다.</b> 그 "모든" 이
 * 분산 합의를 끌어들여, 주도자가 명부를 읽고 최대 3초를 기다리게 만들었다. 세기가 실패하는
 * 경우가 회차마다 새로 나온 것이 재설계의 이유였다
 * ({@code docs/coupon/rebuild-rearchitecture-2026-10-12.md} 1장).
 *
 * <p><b>그래서 전역 시야를 시간으로 바꾼다.</b> "모든 큐를 봐야 한다" 를 <b>"충분히 오래된
 * 번호는 어느 큐에도 없다"</b> 로 바꾼다. 티켓이 큐에 머무는 시간은 배치 창 20밀리초와 확정
 * 대기 800밀리초가 상한이라, 그보다 훨씬 오래된 번호에 행이 없으면 그 번호를 쥔 티켓은 없다.
 *
 * <p><b>발급 경로에 비용을 안 얹는다.</b> 배치 프로필에서만 돌고, 문을 여는 것과 무관하므로
 * 재건 정지가 0 인 성질이 그대로 유지된다.
 */
@Slf4j
@Component
public class CouponSeqFreeSweeper {

    private static final String SCRIPT_PATH = "redis/scripts/coupon-free-sweep.lua";

    /*
     * 한 번에 스크립트로 넘기는 번호 수다.
     * 전부 한 호출에 담으면 그동안 Redis 단일 스레드가 다른 요청을 못 받는다.
     */
    private static final int CHUNK = 500;

    private final StringRedisTemplate redisTemplate;
    private final CouponRepository couponRepository;
    private final CouponConsistencyRepository consistencyRepository;
    private final int margin;
    private final RedisScript<Long> script;
    private final Counter revived;

    public CouponSeqFreeSweeper(CouponSeqRebuildRedis rebuildRedis,
                                CouponRepository couponRepository,
                                CouponConsistencyRepository consistencyRepository,
                                CouponIssueProperties properties,
                                MeterRegistry registry) {
        this.redisTemplate = rebuildRedis.template();
        this.couponRepository = couponRepository;
        this.consistencyRepository = consistencyRepository;
        this.margin = properties.freeSweepMargin();
        this.script = loadScript();
        this.revived = Counter.builder("coupon.seq.free.revived")
                .description("쓸기가 재고로 되돌린 번호 수. 정상 운영에서는 0 이다")
                .register(registry);
    }

    /*
     * 기동 때 읽는다. 못 읽으면 기동이 실패한다.
     * CouponSeqAllocator 와 CouponSeqCommitter 가 같은 이유로 같은 모양을 쓴다.
     */
    private static RedisScript<Long> loadScript() {
        ClassPathResource resource = new ClassPathResource(SCRIPT_PATH);
        try (InputStream in = resource.getInputStream()) {
            return RedisScript.of(new String(in.readAllBytes(), StandardCharsets.UTF_8), Long.class);
        } catch (IOException e) {
            throw new IllegalStateException(SCRIPT_PATH + " 를 읽지 못했다", e);
        }
    }

    /**
     * 열려 있는 선착순 이벤트를 모두 쓸어 본다. 배치 스케줄러가 짧은 주기로 부른다.
     *
     * <p><b>한 쿠폰이 실패해도 나머지를 계속한다.</b> 이것은 되살리기이므로 한 쿠폰의 조회가
     * 깨졌다고 다른 쿠폰의 재고를 묶어 둘 이유가 없다.
     */
    public void sweep() {
        List<Long> couponIds;
        try {
            couponIds = couponRepository.findOpenLimitedEvents();
        } catch (DataAccessException e) {
            log.warn("event=COUPON_SEQ_FREE_SWEEP_LOOKUP_FAILED", e);
            return;
        }
        for (Long couponId : couponIds) {
            try {
                sweepOne(couponId);
            } catch (DataAccessException e) {
                log.warn("event=COUPON_SEQ_FREE_SWEEP_FAILED couponId={}", couponId, e);
            }
        }
    }

    /**
     * 쿠폰 하나를 쓴다. 네 단계가 각각 하나씩 막는다.
     *
     * <pre>
     * 카운터가 있다        아직 안 나간 번호를 재고로 되돌리는 것을 막는다
     * 개수와 최댓값이 다르다  결번이 없으면 무거운 조회를 아예 안 한다
     * 카운터와 거리가 멀다   방금 나가 아직 큐에 있는 번호를 뺏는 것을 막는다
     * 아무도 안 쥐었다      스크립트가 seq 해시로 가린다
     * </pre>
     */
    private void sweepOne(long couponId) {
        String raw = redisTemplate.opsForValue().get(CouponSeqKeys.counter(couponId));
        if (raw == null) {
            // 관리자가 아직 안 열었거나 이미 닫혀 키가 사라졌다
            return;
        }
        int counter;
        try {
            counter = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log.warn("event=COUPON_SEQ_FREE_SWEEP_COUNTER_UNREADABLE couponId={} value={}", couponId, raw);
            return;
        }

        /*
         * 개수와 최댓값을 먼저 견준다. 같으면 1..최댓값이 빈틈없이 다 있다.
         *
         * 순번이 쿠폰마다 유일하므로 그 둘이 같다는 것이 곧 결번이 없다는 뜻이다. 정상 운영의
         * 대부분이 여기서 끝나므로 아래 전체 목록 조회를 안 탄다.
         */
        Optional<Integer> maxSeq = consistencyRepository.findMaxIssueSeq(couponId);
        if (maxSeq.isEmpty()) {
            return;
        }
        long rows = consistencyRepository.findIssueCount(couponId)
                .map(count -> count.actual())
                .orElse(0L);
        if (rows == maxSeq.get()) {
            return;
        }

        List<Integer> candidates = candidates(couponId, counter, maxSeq.get());
        if (candidates.isEmpty()) {
            return;
        }

        long added = 0;
        for (int from = 0; from < candidates.size(); from += CHUNK) {
            added += revive(couponId, candidates.subList(from, Math.min(from + CHUNK, candidates.size())));
        }
        /*
         * 후보가 있는데 하나도 못 넣은 경우를 따로 남긴다.
         *
         * 안 가르면 "되살릴 것이 없었다" 와 "전부 누군가 쥐고 있었다" 가 로그에서 같아진다.
         * 2026-10-13 V-1 에서 되살림이 0 이었는데 그것이 조건 때문인지 연결이 죽어서인지를
         * 로그로 가리지 못해 DB 를 따로 뒤져야 했다.
         */
        if (added == 0) {
            log.info("event=COUPON_SEQ_FREE_ALL_HELD couponId={} counter={} candidates={}",
                    couponId, counter, candidates.size());
            return;
        }
        revived.increment(added);
        log.warn("event=COUPON_SEQ_FREE_REVIVED couponId={} counter={} maxSeq={} rows={}"
                        + " candidates={} revived={}",
                couponId, counter, maxSeq.get(), rows, candidates.size(), added);
    }

    /**
     * 되살릴 후보를 고른다. DB 로 가릴 수 있는 것은 여기서 다 가린다.
     *
     * <p><b>카운터와의 거리로 나이를 잰다.</b> 그 번호의 {@code pending} 항목이 되돌려져 시각을
     * 들고 있는 것이 없다. 카운터는 번호가 나갈 때마다 하나씩 오르므로 그 차이가 곧 "그 뒤로 몇
     * 개가 더 나갔나" 이고, 부하가 몰릴 때는 같은 거리가 더 짧은 시간이 된다. <b>그때가 큐가
     * 빠르게 비는 때이므로 방향이 맞다.</b>
     */
    private List<Integer> candidates(long couponId, int counter, int maxSeq) {
        boolean[] taken = new boolean[maxSeq + 1];
        for (Integer seq : consistencyRepository.findIssueSeqs(couponId)) {
            if (seq != null && seq >= 1 && seq <= maxSeq) {
                taken[seq] = true;
            }
        }
        List<Integer> candidates = new ArrayList<>();
        for (int seq = 1; seq <= maxSeq; seq++) {
            if (!taken[seq] && counter - seq >= margin) {
                candidates.add(seq);
            }
        }
        return candidates;
    }

    /*
     * 가리기와 넣기를 스크립트 하나에 담는다.
     *
     * 나눠 부르면 그 사이에 번호를 받은 회원이 seq 해시에 들어오고, 이쪽은 그것을 못 본 채 쓴다.
     * 그러면 살아 있는 티켓의 번호를 남에게 내준다.
     */
    private long revive(long couponId, List<Integer> chunk) {
        String[] argv = chunk.stream().map(String::valueOf).toArray(String[]::new);
        Long added = redisTemplate.execute(script,
                List.of(CouponSeqKeys.seq(couponId), CouponSeqKeys.free(couponId),
                        CouponSeqKeys.counter(couponId)),
                (Object[]) argv);
        return added == null ? 0 : added;
    }
}
