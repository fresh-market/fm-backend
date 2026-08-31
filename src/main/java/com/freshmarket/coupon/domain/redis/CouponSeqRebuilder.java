package com.freshmarket.coupon.domain.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.domain.entity.Coupon;
import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import com.freshmarket.coupon.domain.repository.MemberCouponSeqRepository;
import com.freshmarket.coupon.domain.repository.MemberCouponSeqRepository.IssuedSeq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 가 이벤트의 키를 잃었을 때 DB 로부터 다시 세운다. 복제본이 승격되면 비동기 복제라
 * 마지막 쓰기들이 없고, 최악이면 네 키가 통째로 없다.
 *
 * <p><b>요청을 막는 장치를 새로 만들지 않는다.</b> 순번 확보 스크립트가 첫 줄에서
 * {@code counter} 가 없으면 {@code -2} 를 돌려주므로, 카운터가 없는 동안에는 아무도 번호를
 * 못 받는다. 그래서 이 클래스가 할 일은 <b>카운터를 가장 마지막에 쓰는 것</b>이고, 그 쓰기가
 * 곧 문을 여는 동작이다.
 *
 * <p>세우는 것은 셋이다.
 *
 * <pre>
 * seq      member_coupon 의 (회원, 순번). 행이 있다는 것은 커밋까지 끝났다는 뜻이라 확정 표시를 붙인다
 * free     1..MAX 중 행이 없는 번호. 이것을 안 채우면 그 번호들이 영영 안 나가 재고가 덜 팔린다
 * counter  MAX(issue_seq). 스크립트가 INCR 을 먼저 하고 그 값을 주므로 이 키는 마지막으로 나간 번호다
 * </pre>
 *
 * <p><b>{@code pending} 은 세우지 않는다.</b> 정의상 DB 에 행이 없는 회원들이라 복원할 근거가
 * 없다. 그리고 복원할 필요도 없다. 그 번호들은 MAX 보다 작으면 {@code free} 가 잡고, 크면
 * 카운터가 그만큼 뒤로 물러나 아직 안 나간 번호가 된다. 오히려 되살리면 이미 죽은 요청을 살아
 * 있는 것으로 올려 두어 회수가 헛되이 기다린다.
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
    private final Duration quiesce;
    private final Duration lockTtl;

    public CouponSeqRebuilder(StringRedisTemplate redisTemplate,
                              CouponRepository couponRepository,
                              MemberCouponSeqRepository seqRepository,
                              CouponSeqInitializer seqInitializer,
                              CouponIssueProperties properties) {
        this.redisTemplate = redisTemplate;
        this.couponRepository = couponRepository;
        this.seqRepository = seqRepository;
        this.seqInitializer = seqInitializer;
        /*
         * 조용해지기를 기다리는 시간으로 회수 기준을 그대로 쓴다.
         * 그 값이 이미 "진행 중인 발급이 결판나기에 충분한 시간" 이라는 뜻으로 쓰이고 있고,
         * 이벤트 종료 배치의 대기도 같은 자리에 선다. 여기서 새 숫자를 만들면 셋이 따로 논다.
         * 확정 대기보다 길다는 것을 CouponIssueProperties 가 생성자에서 이미 강제한다.
         */
        this.quiesce = properties.reclaimAfter();
        // 대기와 쓰기가 끝나기 전에 락이 풀리면 두 인스턴스가 같이 쓴다. 넉넉히 잡는다
        this.lockTtl = properties.reclaimAfter().multipliedBy(3);
    }

    /**
     * 이 쿠폰의 키가 사라졌으면 다시 세운다. 아니면 아무것도 안 한다.
     *
     * <p>여러 갈래로 일찍 돌아가는 것이 이 메서드의 대부분이다. {@code -2} 는 손실만 뜻하지
     * 않는다. 관리자가 아직 안 연 이벤트도 같은 값을 내므로 <b>그 둘을 DB 로 가른다.</b>
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
            // 다른 인스턴스가 하고 있다. 둘이 같이 쓰면 절반씩 채운 상태가 된다
            return;
        }
        try {
            rebuild(couponId, coupon);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("event=COUPON_SEQ_REBUILD_INTERRUPTED couponId={}", couponId);
        } finally {
            releaseLock(couponId, token);
        }
    }

    /**
     * 세우는 순서가 곧 안전장치다.
     *
     * <p>기다리기가 먼저인 이유는 <b>살아 있는 앱의 큐</b> 때문이다. Redis 는 잃었어도 앱은
     * 살아 있을 수 있고, 그 큐에는 이미 번호를 받은 티켓이 떠 있다. 그것들이 DB 로 내려가기
     * 전에 카운터를 세우면 <b>같은 번호가 두 사람에게 간다.</b> 문은 이미 닫혀 있어 새 번호는
     * 안 나가므로, 남은 티켓이 빠지기를 기다리면 된다.
     */
    private void rebuild(long couponId, Coupon coupon) throws InterruptedException {
        log.warn("event=COUPON_SEQ_REBUILD_STARTED couponId={} quiesceSeconds={}",
                couponId, quiesce.toSeconds());
        Thread.sleep(quiesce.toMillis());

        // 기다리는 동안 관리자가 이벤트를 다시 열었을 수 있다. 그러면 그쪽 카운터를 덮으면 안 된다
        if (counterExists(couponId)) {
            log.info("event=COUPON_SEQ_REBUILD_SKIPPED couponId={} reason=counter-appeared", couponId);
            return;
        }

        List<IssuedSeq> issued = seqRepository.findIssuedSeqs(couponId);
        int maxSeq = issued.isEmpty() ? 0 : issued.get(issued.size() - 1).issueSeq();

        writeSeqHash(couponId, issued);
        writeFree(couponId, gaps(issued, maxSeq));
        openGate(couponId, maxSeq, coupon.getIssueEndAt());

        log.warn("event=COUPON_SEQ_REBUILT couponId={} issued={} maxSeq={} freed={}",
                couponId, issued.size(), maxSeq, maxSeq - issued.size());
    }

    /*
     * 행이 있다는 것은 커밋까지 끝났다는 뜻이라 값에 확정 표시를 붙인다.
     * 안 붙이면 회수가 이 번호들을 미확정으로 보고 남에게 넘겨 같은 번호가 두 번 나간다.
     */
    private void writeSeqHash(long couponId, List<IssuedSeq> issued) {
        String key = CouponSeqKeys.seq(couponId);
        for (int from = 0; from < issued.size(); from += CHUNK) {
            List<IssuedSeq> chunk = issued.subList(from, Math.min(from + CHUNK, issued.size()));
            Map<String, String> fields = new HashMap<>(chunk.size());
            chunk.forEach(row ->
                    fields.put(String.valueOf(row.memberId()), row.issueSeq() + COMMITTED_SUFFIX));
            redisTemplate.opsForHash().putAll(key, fields);
        }
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
     * <p>값이 {@code MAX(issue_seq)} 이지 거기에 1 을 더한 값이 아니다. 스크립트가 {@code INCR}
     * 을 먼저 하고 그 결과를 주므로 이 키에 든 것은 <b>마지막으로 나간 번호</b>다. 1 을 더하면
     * 그 번호가 아무에게도 안 가고 죽는데, {@code MAX} 보다 커서 구멍 채우기에도 안 잡힌다.
     *
     * <p>행이 하나도 없으면 0 이다. 이벤트를 여는 {@code prepare} 가 세우는 값과 같아진다.
     */
    private void openGate(long couponId, int maxSeq, java.time.LocalDateTime issueEndAt) {
        redisTemplate.opsForValue().set(CouponSeqKeys.counter(couponId), String.valueOf(maxSeq));
        seqInitializer.applyTtl(couponId, issueEndAt);
        inheritCounterTtl(couponId, CouponSeqKeys.seq(couponId), CouponSeqKeys.free(couponId));
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

    /** 1 부터 최대 순번까지 중 행이 없는 번호다. 이것이 곧 되살릴 재고다. */
    static List<Integer> gaps(List<IssuedSeq> issued, int maxSeq) {
        boolean[] taken = new boolean[maxSeq + 1];
        issued.forEach(row -> taken[row.issueSeq()] = true);

        List<Integer> freed = new ArrayList<>();
        for (int seq = 1; seq <= maxSeq; seq++) {
            if (!taken[seq]) {
                freed.add(seq);
            }
        }
        return freed;
    }

    private boolean counterExists(long couponId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(CouponSeqKeys.counter(couponId)));
    }

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
