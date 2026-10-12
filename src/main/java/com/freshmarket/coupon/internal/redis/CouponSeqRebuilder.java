package com.freshmarket.coupon.internal.redis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.internal.entity.Coupon;
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
 * 키가 통째로 사라졌을 때 카운터를 다시 세운다. 설계 근거는
 * {@code docs/coupon/rebuild-rearchitecture-2026-10-12.md} 에 있다.
 *
 * <p><b>이 클래스는 아무도 기다리지 않는다.</b> 예전에는 주도자가 명부를 읽어 참가자 수를 세고
 * 전원이 자기 큐를 올리기를 최대 3초 기다렸다. 그 세기가 실패하는 경우가 회차마다 새로 나왔고,
 * 세는 방법을 고칠 때마다 구멍이 자리를 옮겼다. 다섯 결함이 모두 "주도자가 참가자를 세는 데
 * 실패한 것" 이었다 (같은 문서 1장).
 *
 * <p><b>그래서 세는 방법을 고치지 않고 셀 필요를 없앴다.</b> 카운터는 올리기만 하는 값이라
 * 전체 최댓값이 각자가 아는 최댓값들의 최댓값이다. 그러면 각 인스턴스가 자기 항만 올려도 결과가
 * 수렴하므로, <b>주도자도 락도 명부도 기여 수집도 필요 없다.</b>
 *
 * <pre>
 * 올릴 값 = max(DB 의 MAX(issue_seq),  이 인스턴스가 받아 본 최대 순번)
 * </pre>
 *
 * <p><b>오른쪽 두 항이 전부 지역 정보다.</b> 그래서 여러 인스턴스가 동시에 돌아도 결함이 아니고
 * 오히려 수렴을 돕는다. 멱등이라 한 장애에 다섯 번 돌아도 결과가 같다.
 *
 * <p><b>안전은 이 클래스가 지키는 것이 아니다.</b> 초과 발급과 1인 2매는
 * {@code uk_mc_coupon_seq} 와 {@code uk_mc_coupon_member} 가 막는다. 카운터가 낮게 서도 그
 * 번호가 두 번 나갈 뿐이고 한쪽이 유니크 키에 걸려 결번으로 남는다. <b>이 클래스가 줄이는 것은
 * 결번이고 품질이다.</b> 그것을 안전으로 오해해 차단형 합의로 지은 것이 설계가 무거워진 까닭이다
 * (같은 문서 2장).
 *
 * <p><b>{@code seq} 는 DB 에서 다시 세운다.</b> 이것만은 뺄 수 없다. 발급 경로에 DB 중복 검사가
 * 없어서 "이미 발급됐다" 를 이 해시에서만 알기 때문이다. 비어 있으면 이미 받은 회원의 재시도가
 * 번호를 하나 더 태우고, 그 행이 {@code uk_mc_coupon_member} 에 걸려 결번이 된다.
 *
 * <p><b>그 읽기는 합의와 무관하다.</b> 무거워진 원인은 남을 세고 기다린 쪽이었고, DB 를 읽는
 * 것은 이 인스턴스 혼자 할 수 있는 일이다. 그래서 읽기는 남기고 기다림만 걷었다.
 *
 * <p><b>{@code free} 는 세우지 않는다.</b> {@code 1..최댓값} 에서 DB 와 <b>모든</b> 큐를 빼야
 * 하므로 그것만 전역 시야를 요구한다. 내 큐만 빼고 세우면 남이 쥔 번호를 되살려 같은 번호가 두
 * 번 나간다. 주인을 잃은 번호는 {@code pending} 회수가 되살리고, 그것째 잃은 번호는 정합성
 * 배치가 센다 (같은 문서 5.4).
 */
@Slf4j
@Component
public class CouponSeqRebuilder {

    private static final String OPEN_GATE_SCRIPT_PATH = "redis/scripts/coupon-open-gate.lua";

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
    private final Timer duration;
    private final RedisScript<Long> openGateScript;

    public CouponSeqRebuilder(CouponSeqRebuildRedis rebuildRedis,
                              CouponRepository couponRepository,
                              MemberCouponSeqRepository seqRepository,
                              CouponSeqInitializer seqInitializer,
                              CouponSeqContributor contributor,
                              CouponSeqMarks marks,
                              MeterRegistry registry) {
        this.redisTemplate = rebuildRedis.template();
        this.couponRepository = couponRepository;
        this.seqRepository = seqRepository;
        this.seqInitializer = seqInitializer;
        this.contributor = contributor;
        this.marks = marks;
        /*
         * 복구에 걸린 시간을 잰다. 이제 이것은 발급이 멈춘 시간이 아니다.
         *
         * 예전에는 재건이 락으로 발급을 막았으므로 이 값이 곧 정지 시간이었고 0.244 ~ 0.856초였다.
         * 지금은 막는 것이 없어 그 쿠폰의 발급은 -2 를 받는 요청만 혼잡으로 돌아간다. 그래서
         * 이름을 정지가 아니라 소요로 읽어야 한다.
         */
        this.duration = Timer.builder("coupon.seq.rebuild.duration")
                .description("카운터를 다시 세우는 데 걸린 시간. 발급을 막지 않으므로 정지 시간이 아니다")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.openGateScript = loadScript(OPEN_GATE_SCRIPT_PATH);
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
     * 이 쿠폰의 카운터를 다시 세운다. 같은 쿠폰에 여러 인스턴스가 동시에 들어와도 된다.
     *
     * <p><b>깨우는 신호는 손실만 뜻하지 않는다.</b> 관리자가 아직 안 연 이벤트도 {@code -2} 를
     * 내므로 그 둘을 DB 로 가른다.
     *
     * <p><b>자기 큐를 카운터보다 먼저 되살린다.</b> 카운터를 세우는 것이 곧 문을 여는 것이라,
     * 순서가 뒤바뀌면 이미 번호를 쥔 회원이 {@code seq} 에 없는 틈에 재시도해 번호를 하나 더
     * 태운다. 그 둘 중 하나가 {@code uk_mc_coupon_member} 에 걸려 결번이 된다.
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

        long startedAt = System.nanoTime();

        // 커밋된 회원을 DB 에서 되살린다. 이 해시가 "이미 발급됐다" 를 아는 유일한 자리다
        List<IssuedSeq> issued = seqRepository.findIssuedSeqs(couponId);
        writeSeqHash(couponId, issued);

        /*
         * 내 몫만 되살린다. 남의 큐는 남이 같은 자리에 되살린다.
         *
         * 티켓 하나는 정확히 한 인스턴스에만 있으므로 전원이 자기 몫을 쓰면 빠지는 티켓이 없다.
         * 그런데 그것을 확인하려고 기다릴 필요가 없다. 늦게 쓰는 대가 있으면 그 대의 회원만 그
         * 사이 재시도에서 결번을 낼 수 있고, 예전 설계는 그 하나를 막으려고 전체 발급을 3초
         * 멈췄다 (docs/coupon/rebuild-rearchitecture-2026-10-12.md 5.3).
         */
        contributor.restore(couponId);

        int dbMax = maxSeq(issued);
        int mark = marks.of(couponId);
        int bound = Math.max(dbMax, mark);

        long opened = openGate(couponId, bound, coupon.getIssueEndAt());
        duration.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);

        log.warn("event=COUPON_SEQ_REBUILT couponId={} issued={} dbMax={} mark={} bound={} counter={}"
                        + " rebuildMillis={}",
                couponId, issued.size(), dbMax, mark, bound, opened,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
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
        putAllInChunks(CouponSeqKeys.seq(couponId), fields);
    }

    /*
     * 항목마다 한 번씩 치면 만 건이 만 번의 왕복이 된다.
     * 그 시간 동안 이 쿠폰의 요청은 -2 를 받고 돌아가므로 왕복 수가 곧 돌려보내는 요청 수다.
     */
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

    /** 나간 번호 중 가장 큰 것이다. 개수로 더하면 결번만큼 모자라게 센다. */
    private static int maxSeq(List<IssuedSeq> issued) {
        int max = 0;
        for (IssuedSeq row : issued) {
            max = Math.max(max, row.issueSeq());
        }
        return max;
    }

    /**
     * 카운터를 세워 문을 연다. 이 메서드가 도는 순간부터 요청이 번호를 받기 시작한다.
     *
     * <p><b>올리기만 한다.</b> 그 성질이 이 설계의 중심이다. 여러 인스턴스가 각자 읽고 각자 쓰는데
     * 읽는 순서와 쓰는 순서가 보장되지 않으므로, 낮은 값이 높은 값을 덮을 수 있으면 복구가 고치려는
     * 증상을 복구가 만든다. 올리기만 하면 순서가 무관해지고 <b>그래서 락이 필요 없다.</b>
     *
     * <p>비교와 쓰기를 스크립트 하나에 담는다. 읽고 나서 쓰면 그 사이에 남의 복구나 발급이 끼어든다.
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

    /*
     * 넷의 수명은 counter 가 들고 나머지가 따라간다.
     * 평상시에는 스크립트가 키를 만들면서 물려받는데, 복구는 카운터보다 먼저 만들어 그 경로를
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
     * <p>준비 단계({@code CouponSeqInitializer})가 카운터를 0 으로 세운다. 이 복구가 세울 값은
     * 나간 번호의 최댓값이라 0 이 될 수 없다. <b>0 이 보이면 그쪽이 새로 연 것이므로 덮지 않는다.</b>
     * 덮으면 지난 이벤트의 최댓값까지 건너뛰어 그만큼의 재고를 통째로 버린다.
     */
    private boolean reprepared(long couponId) {
        String raw = redisTemplate.opsForValue().get(CouponSeqKeys.counter(couponId));
        return "0".equals(raw);
    }
}
