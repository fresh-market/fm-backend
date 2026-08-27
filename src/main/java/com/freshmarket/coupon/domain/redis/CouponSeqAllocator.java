package com.freshmarket.coupon.domain.redis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 선착순 발급의 순번 확보. 쿠폰과 회원의 식별자만 받고 엔티티는 전혀 모른다.
 *
 * <p>키 넷(seq, free, counter, pending)을 한 번에 다뤄야 해서 Lua 스크립트 하나로 처리한다.
 * 나눠 부르면 그 사이에 다른 요청이 끼어들어 같은 번호가 두 번 나갈 수 있다. 스크립트가 무엇을
 * 어떤 순서로 보는지는 {@code docs/coupon/coupon.md} 3장에 있다.
 *
 * <p>Redis 장애 시 {@code DataAccessException} 을 그대로 던진다. 혼잡 응답(503)으로 바꾸는 것은
 * 호출자의 몫이고, 이 클래스는 대체 순번 발급기를 두지 않는다.
 */
@Component
public class CouponSeqAllocator {

    private static final String SCRIPT_PATH = "redis/scripts/coupon-issue-seq.lua";

    private static final String KEY_PREFIX = "coupon:";
    private static final String SEQ_SUFFIX = ":seq";
    private static final String FREE_SUFFIX = ":free";
    private static final String COUNTER_SUFFIX = ":counter";
    private static final String PENDING_SUFFIX = ":pending";

    private static final String COMMITTED_DELIMITER = ":";
    private static final String SOLD_OUT = "-1";
    private static final String NOT_PREPARED = "-2";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> allocateScript;
    private final Duration reclaimAfter;

    /*
     * reclaimAfter 를 호출 인자가 아니라 설정으로 받는다.
     * 회수의 안전은 모든 호출부가 같은 값을 쓰는 데 달려 있어서, 부를 때마다 넘기면 한 군데만
     * 달라져도 아직 살아 있는 요청의 번호를 뺏는다. 요청 예산보다 길게 잡아야 한다.
     */
    public CouponSeqAllocator(StringRedisTemplate redisTemplate,
                              @Value("${coupon.issue.reclaim-after:60s}") Duration reclaimAfter) {
        this.redisTemplate = redisTemplate;
        this.reclaimAfter = reclaimAfter;
        this.allocateScript = loadAllocateScript();
    }

    /*
     * 스크립트를 여기서 다 읽어 둔다.
     * setLocation 으로 걸어 두면 첫 실행 때 파일을 읽으므로, 패키징이 어긋났을 때 그 사실을
     * 이벤트 첫 요청에서 알게 된다. 생성자에서 읽으면 기동이 대신 실패한다.
     */
    private static RedisScript<String> loadAllocateScript() {
        ClassPathResource resource = new ClassPathResource(SCRIPT_PATH);
        try (InputStream in = resource.getInputStream()) {
            return RedisScript.of(new String(in.readAllBytes(), StandardCharsets.UTF_8), String.class);
        } catch (IOException e) {
            throw new IllegalStateException(SCRIPT_PATH + " 를 읽지 못했다", e);
        }
    }

    /**
     * 회원에게 순번을 준다. 이미 번호를 받은 회원이면 새로 태우지 않고 그 번호를 그대로 돌려준다.
     *
     * @param issueLimit 쿠폰의 총 수량. 스크립트가 이 값을 넘는 순번을 내주지 않는다
     * @return 네 갈래 중 하나. 갈래마다 낼 응답이 다르다({@link SeqOutcome})
     */
    public SeqOutcome allocate(long couponId, long memberId, int issueLimit) {
        String raw = redisTemplate.execute(
                allocateScript,
                List.of(seqKey(couponId), freeKey(couponId), counterKey(couponId), pendingKey(couponId)),
                String.valueOf(memberId),
                String.valueOf(issueLimit),
                String.valueOf(reclaimAfter.toMillis())
        );
        return parse(raw);
    }

    private SeqOutcome parse(String raw) {
        if (NOT_PREPARED.equals(raw)) {
            return new SeqOutcome.NotPrepared();
        }
        if (SOLD_OUT.equals(raw)) {
            return new SeqOutcome.SoldOut();
        }

        /*
         * 스크립트는 언제나 문자열을 돌려주므로 여기 오면 스크립트와 이 클래스가 어긋난 것이다.
         * 혼잡으로 흘려보내면 그 어긋남이 재시도에 묻혀 안 드러나므로 그냥 터뜨린다.
         */
        if (raw == null) {
            throw new IllegalStateException(SCRIPT_PATH + " 가 nil 을 돌려줬다");
        }

        // 확정 표시("6:1")가 붙어 있으면 커밋까지 끝난 것이다.
        int delimiter = raw.indexOf(COMMITTED_DELIMITER);
        if (delimiter < 0) {
            return new SeqOutcome.Allocated(Integer.parseInt(raw));
        }
        return new SeqOutcome.AlreadyIssued(Integer.parseInt(raw.substring(0, delimiter)));
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
}
