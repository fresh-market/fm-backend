package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.freshmarket.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/*
 * 이벤트 준비와 정리가 실제 Valkey 에서 의도대로 도는지 본다.
 *
 * 특히 준비가 이전 잔재를 지우는지를 여기서 못 박는다. 종료 배치가 안 돌아 매핑이 남아 있으면
 * 스크립트가 그 회원들을 이미 발급받은 것으로 보고 새 이벤트에서 아무것도 안 준다.
 */
@SpringBootTest
class CouponSeqInitializerIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 4343L;

    private static final String SEQ = "coupon:4343:seq";
    private static final String FREE = "coupon:4343:free";
    private static final String COUNTER = "coupon:4343:counter";
    private static final String PENDING = "coupon:4343:pending";
    private static final List<String> KEYS = List.of(SEQ, FREE, COUNTER, PENDING);

    @Autowired
    private CouponSeqInitializer initializer;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void 키를_비운다() {
        redisTemplate.delete(KEYS);
    }

    @Test
    void 준비가_카운터를_0으로_세운다() {
        initializer.prepare(COUPON_ID, null);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("0");
    }

    @Test
    void 준비가_지난_이벤트의_잔재를_지운다() {
        // given 종료 배치가 안 돌아 지난 이벤트가 그대로 남아 있다
        redisTemplate.opsForHash().put(SEQ, "5001", "6:1");
        redisTemplate.opsForZSet().add(FREE, "3", 3);
        redisTemplate.opsForZSet().add(PENDING, "5002", System.currentTimeMillis());
        redisTemplate.opsForValue().set(COUNTER, "9999");

        // when
        initializer.prepare(COUPON_ID, null);

        // then
        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("0");
        assertThat(redisTemplate.opsForHash().get(SEQ, "5001")).isNull();
        assertThat(redisTemplate.opsForZSet().score(FREE, "3")).isNull();
        assertThat(redisTemplate.opsForZSet().score(PENDING, "5002")).isNull();
    }

    // 마감이 있으면 그물로 TTL 을 건다. 종료 배치가 안 돌았을 때를 위한 것이다
    @Test
    void 마감이_있으면_카운터에_만료_시각을_건다() {
        initializer.prepare(COUPON_ID, LocalDateTime.now().plusMinutes(10));

        Long ttl = redisTemplate.getExpire(COUNTER, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(600L, 660L);
    }

    // 마감이 없으면 끝나는 시각이 없어 TTL 을 걸 기준이 없다. 관리자가 끌 때까지 남는다
    @Test
    void 마감이_없으면_만료_시각을_걸지_않는다() {
        initializer.prepare(COUPON_ID, null);

        assertThat(redisTemplate.getExpire(COUNTER, TimeUnit.SECONDS)).isEqualTo(-1L);
    }

    /*
     * 관리자가 발급 시각을 바꾼 뒤에 부른다.
     * 옛 마감으로 계산된 TTL 이 남아 있으면 키가 새 마감보다 먼저 사라진다.
     */
    @Test
    void 만료_시각을_다시_걸_수_있다() {
        initializer.prepare(COUPON_ID, LocalDateTime.now().plusMinutes(10));

        initializer.applyTtl(COUPON_ID, LocalDateTime.now().plusMinutes(30));

        Long ttl = redisTemplate.getExpire(COUNTER, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(1800L, 1860L);
    }

    @Test
    void 정리가_네_키를_모두_지운다() {
        redisTemplate.opsForHash().put(SEQ, "5001", "6");
        redisTemplate.opsForZSet().add(FREE, "3", 3);
        redisTemplate.opsForValue().set(COUNTER, "10");
        redisTemplate.opsForZSet().add(PENDING, "5001", System.currentTimeMillis());

        initializer.clear(COUPON_ID);

        assertThat(redisTemplate.countExistingKeys(KEYS)).isZero();
    }
}
