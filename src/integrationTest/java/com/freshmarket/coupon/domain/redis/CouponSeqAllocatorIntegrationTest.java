package com.freshmarket.coupon.domain.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/*
 * 순번 확보 스크립트와 그 래퍼를 실제 Valkey 로 검증한다.
 *
 * 여기서 보는 것은 앱 코드 하나가 아니라 Lua 와 Java 가 맞물리는 자리다. KEYS 와 ARGV 의 순서,
 * 반환 문자열의 해석, 회수 경계는 어느 한쪽만 고쳐도 조용히 어긋나므로 단위 테스트로는 못 잡는다.
 */
@SpringBootTest
class CouponSeqAllocatorIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 4242L;
    private static final int ISSUE_LIMIT = 3;

    private static final String SEQ = "coupon:4242:seq";
    private static final String FREE = "coupon:4242:free";
    private static final String COUNTER = "coupon:4242:counter";
    private static final String PENDING = "coupon:4242:pending";

    @Autowired
    private CouponSeqAllocator allocator;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CouponIssueProperties properties;

    @BeforeEach
    void 키를_비운다() {
        redisTemplate.delete(List.of(SEQ, FREE, COUNTER, PENDING));
    }

    // 카운터가 서야 이벤트가 시작된 것이다. 이 준비 절차를 배치가 맡는다
    private void 이벤트를_연다() {
        redisTemplate.opsForValue().set(COUNTER, "0");
    }

    // 준비 단계가 카운터에만 수명을 건다. 나머지 셋은 아직 없어 그때는 걸 수 없다
    private void 수명이_있는_이벤트를_연다() {
        이벤트를_연다();
        redisTemplate.expire(COUNTER, Duration.ofMinutes(10));
    }

    private Long 남은_수명(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    /*
     * 네 키는 함께 살고 함께 죽어야 한다. 하나만 남으면 나머지가 거짓말을 한다.
     *
     * 준비 단계에서는 카운터에만 걸 수 있다. 나머지 셋이 그때 없어서 EXPIREAT 이 아무 일도
     * 안 하기 때문이다. 그래서 만드는 자리가 카운터의 만료 시각을 물려받는지를 여기서 본다.
     */
    @Test
    void 만들어지는_키가_카운터의_수명을_물려받는다() {
        수명이_있는_이벤트를_연다();

        allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT);

        assertThat(남은_수명(SEQ)).isNotNull().isBetween(1L, 600L);
        assertThat(남은_수명(PENDING)).isNotNull().isBetween(1L, 600L);
    }

    // 마감이 없으면 카운터에도 수명이 없다. 물려줄 것이 없으므로 나머지도 안 건다
    @Test
    void 카운터에_수명이_없으면_아무_키에도_안_건다() {
        이벤트를_연다();

        allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT);

        assertThat(남은_수명(SEQ)).isEqualTo(-1L);
        assertThat(남은_수명(PENDING)).isEqualTo(-1L);
    }

    /*
     * 앱은 스크립트 본문을 자기 힙에 들고 있을 뿐 서버에 올려 두지는 않는다.
     * 스프링이 EVALSHA 를 먼저 시도하고 서버가 그 sha 를 모르면 NOSCRIPT 로 튕긴 뒤 EVAL 로
     * 다시 보내는데, 그 대가를 이벤트의 첫 요청이 문다. 발급이 가장 몰리는 순간이다.
     *
     * 이 시험은 preloadScript 하나만 본다. 이벤트를 여는 경로가 그것을 실제로 부르는지는
     * CouponEventOpenedListenerIntegrationTest 가 따로 본다.
     */
    @Test
    void 스크립트를_미리_올리면_서버가_그것을_안다() throws Exception {
        String sha = 스크립트_sha();
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.scriptingCommands().scriptFlush();
            return null;
        });
        assertThat(서버가_아는가(sha)).isFalse();

        allocator.preloadScript();

        assertThat(서버가_아는가(sha)).isTrue();
    }

    private String 스크립트_sha() throws Exception {
        try (var in = new ClassPathResource("redis/scripts/coupon-issue-seq.lua").getInputStream()) {
            return RedisScript.of(new String(in.readAllBytes(), StandardCharsets.UTF_8), String.class).getSha1();
        }
    }

    private boolean 서버가_아는가(String sha) {
        List<Boolean> found = redisTemplate.execute((RedisCallback<List<Boolean>>) connection ->
                connection.scriptingCommands().scriptExists(sha));
        return found != null && !found.isEmpty() && Boolean.TRUE.equals(found.get(0));
    }

    @Test
    void 카운터가_없으면_순번을_내주지_않는다() {
        assertThat(allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT))
                .isInstanceOf(SeqOutcome.NotPrepared.class);
        assertThat(redisTemplate.hasKey(COUNTER)).isFalse();
    }

    @Test
    void 순번은_1부터_차례로_나간다() {
        이벤트를_연다();

        assertThat(allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT)).isEqualTo(new SeqOutcome.Allocated(1));
        assertThat(allocator.allocate(COUPON_ID, 2L, ISSUE_LIMIT)).isEqualTo(new SeqOutcome.Allocated(2));
        assertThat(allocator.allocate(COUPON_ID, 3L, ISSUE_LIMIT)).isEqualTo(new SeqOutcome.Allocated(3));
    }

    @Test
    void 같은_회원이_다시_요청하면_같은_번호를_받는다() {
        이벤트를_연다();
        allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT);

        assertThat(allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT)).isEqualTo(new SeqOutcome.Allocated(1));
        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("1");
    }

    @Test
    void 한도를_넘으면_소진이고_카운터가_부풀지_않는다() {
        이벤트를_연다();
        allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT);
        allocator.allocate(COUPON_ID, 2L, ISSUE_LIMIT);
        allocator.allocate(COUPON_ID, 3L, ISSUE_LIMIT);

        assertThat(allocator.allocate(COUPON_ID, 4L, ISSUE_LIMIT)).isInstanceOf(SeqOutcome.SoldOut.class);

        // DECR 이 되돌리지 않으면 재건 때 MAX(issue_seq) 와 어긋난다
        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("3");
    }

    @Test
    void 반납된_순번이_새_번호보다_먼저_나간다() {
        이벤트를_연다();
        redisTemplate.opsForZSet().add(FREE, "2", 2);

        assertThat(allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT)).isEqualTo(new SeqOutcome.Allocated(2));
        assertThat(redisTemplate.opsForZSet().size(FREE)).isZero();
    }

    @Test
    void 커밋이_끝난_회원은_이미_발급으로_답한다() {
        이벤트를_연다();
        allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT);
        플러시가_확정_표시를_붙인다(1L, 1);

        assertThat(allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT)).isEqualTo(new SeqOutcome.AlreadyIssued(1));
    }

    @Test
    void 묶인_순번은_기준_시간이_지나야_회수된다() {
        이벤트를_연다();
        모두_소진시킨다();

        // 방금 번호를 받은 것들이라 아직 회수 대상이 아니다
        assertThat(allocator.allocate(COUPON_ID, 9L, ISSUE_LIMIT)).isInstanceOf(SeqOutcome.SoldOut.class);

        오래_묶인_것으로_만든다(2L);

        assertThat(allocator.allocate(COUPON_ID, 9L, ISSUE_LIMIT)).isEqualTo(new SeqOutcome.Allocated(2));
        assertThat(redisTemplate.opsForHash().get(SEQ, "2")).isNull();
    }

    /*
     * 플러시가 확정 표시를 붙이면서 pending 에서 빼는 것이 정상이라 이 조합은 평소에 안 생긴다.
     * 한쪽만 실패했을 때 이미 발급된 번호가 남에게 넘어가지 않는지를 본다.
     */
    @Test
    void 확정_표시가_붙은_것은_회수하지_않는다() {
        이벤트를_연다();
        모두_소진시킨다();
        플러시가_확정_표시를_붙인다(2L, 2);
        오래_묶인_것으로_만든다(2L);

        assertThat(allocator.allocate(COUPON_ID, 9L, ISSUE_LIMIT)).isInstanceOf(SeqOutcome.SoldOut.class);
        assertThat(redisTemplate.opsForHash().get(SEQ, "2")).isEqualTo("2:1");
        assertThat(redisTemplate.opsForZSet().score(PENDING, "2")).isNull();
    }

    private void 모두_소진시킨다() {
        allocator.allocate(COUPON_ID, 1L, ISSUE_LIMIT);
        allocator.allocate(COUPON_ID, 2L, ISSUE_LIMIT);
        allocator.allocate(COUPON_ID, 3L, ISSUE_LIMIT);
    }

    private void 플러시가_확정_표시를_붙인다(long memberId, int seq) {
        redisTemplate.opsForHash().put(SEQ, String.valueOf(memberId), seq + ":1");
        redisTemplate.opsForZSet().remove(PENDING, String.valueOf(memberId));
    }

    /*
     * 회수 경계는 pending 점수를 옛 값으로 심어서 잰다.
     * 시각을 Redis 가 스스로 매기므로 앱 시계를 흔들 수 없고, 흔들 필요도 없다.
     */
    private void 오래_묶인_것으로_만든다(long memberId) {
        long wellPast = System.currentTimeMillis() - properties.reclaimAfter().toMillis() - 1_000;
        redisTemplate.opsForZSet().add(PENDING, String.valueOf(memberId), wellPast);
    }
}
