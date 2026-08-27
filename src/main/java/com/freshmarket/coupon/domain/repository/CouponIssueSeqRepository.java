package com.freshmarket.coupon.domain.repository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 선착순 발급의 순번 확보. 순수 Redis 저장소라 Coupon/Member 엔티티를 전혀 모른다.
 *
 * <p>키 넷(seq, free, counter, pending)을 한 번에 다뤄야 해서 Lua 스크립트 하나로 처리한다.
 * 나눠 부르면 그 사이에 다른 요청이 끼어들어 같은 번호가 두 번 나갈 수 있다. 설계 근거는
 * {@code docs/coupon/coupon.md} 3장에 있다.
 *
 * <p>Redis 장애 시 {@code DataAccessException} 을 그대로 던진다. 이것을 혼잡 응답(503)으로
 * 바꾸는 것은 호출자의 책임이다. 이 클래스는 대체 순번 발급기를 두지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class CouponIssueSeqRepository {

    private static final String KEY_PREFIX = "coupon:";
    private static final String SEQ_SUFFIX = ":seq";
    private static final String FREE_SUFFIX = ":free";
    private static final String COUNTER_SUFFIX = ":counter";
    private static final String PENDING_SUFFIX = ":pending";

    private static final String COMMITTED_DELIMITER = ":";
    private static final String SOLD_OUT = "-1";
    private static final String NOT_PREPARED = "-2";

    private static final RedisScript<String> ALLOCATE_SCRIPT = loadAllocateScript();

    private static RedisScript<String> loadAllocateScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/scripts/coupon-issue-seq.lua"));
        script.setResultType(String.class);
        return script;
    }

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    /**
     * 회원에게 순번을 준다. 이미 번호를 받은 회원이면 새로 태우지 않고 그 번호를 그대로 돌려준다.
     *
     * @param issueLimit   쿠폰의 총 수량. 스크립트가 이 값을 넘는 순번을 내주지 않는다
     * @param reclaimAfter 이만큼 커밋되지 않은 순번은 소진 시점에 회수해 다른 회원에게 넘긴다.
     *                     요청 예산보다 길어야 아직 살아 있는 요청의 번호를 뺏지 않는다
     * @return ALLOCATED(이 번호로 발급을 진행한다) / ALREADY_ISSUED(커밋까지 끝났다) /
     *         SOLD_OUT(재고가 없고 회수할 것도 없다) / NOT_PREPARED(이벤트 준비 전이거나 재건 중이다)
     */
    public SeqOutcome allocate(long couponId, long memberId, int issueLimit, Duration reclaimAfter) {
        String raw = redisTemplate.execute(
                ALLOCATE_SCRIPT,
                List.of(seqKey(couponId), freeKey(couponId), counterKey(couponId), pendingKey(couponId)),
                String.valueOf(memberId),
                String.valueOf(issueLimit),
                String.valueOf(clock.millis()),
                String.valueOf(reclaimAfter.toMillis())
        );
        return parse(raw);
    }

    private SeqOutcome parse(String raw) {
        /*
         * 스크립트는 언제나 문자열을 돌려주므로 null 은 오지 않는다.
         * 와도 순번을 못 받은 것은 같으므로 준비 전과 같이 다뤄 혼잡으로 흘려보낸다.
         */
        if (raw == null || NOT_PREPARED.equals(raw)) {
            return SeqOutcome.notPrepared();
        }
        if (SOLD_OUT.equals(raw)) {
            return SeqOutcome.soldOut();
        }

        // 확정 표시("6:1")가 붙어 있으면 커밋까지 끝난 것이라 DB 를 안 치고 그대로 답할 수 있다.
        int delimiter = raw.indexOf(COMMITTED_DELIMITER);
        if (delimiter < 0) {
            return SeqOutcome.allocated(Integer.parseInt(raw));
        }
        return SeqOutcome.alreadyIssued(Integer.parseInt(raw.substring(0, delimiter)));
    }

    private String seqKey(long couponId) {
        return KEY_PREFIX + couponId + SEQ_SUFFIX;
    }

    private String freeKey(long couponId) {
        return KEY_PREFIX + couponId + FREE_SUFFIX;
    }

    private String counterKey(long couponId) {
        return KEY_PREFIX + couponId + COUNTER_SUFFIX;
    }

    private String pendingKey(long couponId) {
        return KEY_PREFIX + couponId + PENDING_SUFFIX;
    }

    /*
     * allocate() 의 네 갈래 결과다.
     * Optional 하나로는 "재고가 없다" 와 "아직 준비되지 않았다" 를 구분하지 못해 따로 뒀다.
     * 앞은 최종이라 4xx 로 끊고 뒤는 다시 시도할 값이 있어 503 과 Retry-After 로 답한다.
     */
    public record SeqOutcome(Status status, Integer seq) {

        public enum Status { ALLOCATED, ALREADY_ISSUED, SOLD_OUT, NOT_PREPARED }

        public static SeqOutcome allocated(int seq) {
            return new SeqOutcome(Status.ALLOCATED, seq);
        }

        public static SeqOutcome alreadyIssued(int seq) {
            return new SeqOutcome(Status.ALREADY_ISSUED, seq);
        }

        public static SeqOutcome soldOut() {
            return new SeqOutcome(Status.SOLD_OUT, null);
        }

        public static SeqOutcome notPrepared() {
            return new SeqOutcome(Status.NOT_PREPARED, null);
        }

        public boolean isAllocated() {
            return status == Status.ALLOCATED;
        }

        public boolean isAlreadyIssued() {
            return status == Status.ALREADY_ISSUED;
        }

        public boolean isSoldOut() {
            return status == Status.SOLD_OUT;
        }
    }
}
