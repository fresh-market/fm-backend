package com.freshmarket.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.coupon.domain.service.CouponEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/*
 * 이벤트를 여는 트랜잭션이 커밋된 뒤에 리스너가 스크립트를 올리는지 본다.
 *
 * 서비스는 이벤트를 발행할 뿐이라 단위 시험은 발행까지만 볼 수 있다. 그 발행이 실제 트랜잭션을
 * 지나 리스너에 닿고 스크립트가 서버에 올라가기까지를 이 시험이 잇는다.
 *
 * 단계가 AFTER_COMMIT 인지 IN_COMMIT 인지까지는 이 시험이 가르지 않는다. 그것은 애너테이션이
 * 정하는 값이라 여기서 잴 것이 아니고, 여기서 볼 것은 배선이 실제로 이어져 있는가다.
 */
@SpringBootTest
@Sql("/sql/coupon-issue-fixture.sql")
class CouponEventOpenedListenerIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;

    private static final String SEQ = "coupon:9001:seq";
    private static final String FREE = "coupon:9001:free";
    private static final String COUNTER = "coupon:9001:counter";
    private static final String PENDING = "coupon:9001:pending";

    @Autowired
    private CouponEventService couponEventService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /*
     * 픽스처의 쿠폰은 켜진 채로 들어온다.
     * 끄지 않으면 activateIfInactive 가 0 행을 돌려주고 open 이 그 자리에서 되돌아간다.
     */
    @BeforeEach
    void 꺼진_이벤트와_빈_서버로_되돌린다() {
        jdbcTemplate.update("UPDATE coupon SET is_active = FALSE WHERE coupon_id = ?", COUPON_ID);
        redisTemplate.delete(List.of(SEQ, FREE, COUNTER, PENDING));
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.scriptingCommands().scriptFlush();
            return null;
        });
    }

    @Test
    void 이벤트를_열면_커밋_뒤에_스크립트가_서버에_올라간다() throws Exception {
        String sha = 스크립트_sha();
        assertThat(서버가_아는가(sha)).isFalse();

        couponEventService.open(COUPON_ID);

        assertThat(서버가_아는가(sha)).isTrue();
        // 카운터는 같은 트랜잭션 안에서 서므로 열림과 함께 이미 있어야 한다
        assertThat(redisTemplate.hasKey(COUNTER)).isTrue();
    }

    /*
     * 이미 열린 이벤트를 또 열면 open 이 카운터를 다시 세우지 않고 되돌아간다.
     * 그때는 이벤트를 발행하지 않으므로 리스너도 안 돈다.
     */
    @Test
    void 이미_열린_이벤트는_스크립트를_다시_올리지_않는다() throws Exception {
        jdbcTemplate.update("UPDATE coupon SET is_active = TRUE WHERE coupon_id = ?", COUPON_ID);

        couponEventService.open(COUPON_ID);

        assertThat(서버가_아는가(스크립트_sha())).isFalse();
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
}
