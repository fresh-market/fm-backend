package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.freshmarket.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/*
 * 주인 잃은 번호를 되살리는 경로를 실제 Redis 와 MySQL 로 본다.
 *
 * 단위 시험으로는 가리는 쪽을 못 본다. 스크립트가 seq 해시를 통째로 읽어 "누가 쥔 번호" 를
 * 만드는 일이라, 목으로 재면 목에게 시킨 것을 그대로 돌려받는다. 확정 표시를 떼고 보는 것과
 * 정렬집합의 점수 모양도 뒤에 순번 확보 스크립트가 그대로 읽어 가는 값이라 실물로 봐야 한다.
 *
 * 나이 기준을 10 으로 줄인다. 재는 것은 그 값의 크기가 아니라 그 조건이 거는지다.
 */
@SpringBootTest
@TestPropertySource(properties = "coupon.issue.free-sweep-margin=10")
@Sql("/sql/coupon-issue-fixture.sql")
class CouponSeqFreeSweepIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;

    private static final String SEQ = "coupon:9001:seq";
    private static final String FREE = "coupon:9001:free";
    private static final String COUNTER = "coupon:9001:counter";
    private static final String PENDING = "coupon:9001:pending";

    @Autowired
    private CouponSeqFreeSweeper sut;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 키를_비운다() {
        redisTemplate.delete(List.of(SEQ, FREE, COUNTER, PENDING));
        jdbcTemplate.update("DELETE FROM member_coupon WHERE coupon_id = ?", COUPON_ID);
    }

    /*
     * 이 시험이 쓸기의 전부를 한 번에 본다.
     *
     * 발급 행을 1, 2, 4 로 심어 3 번이 결번이 되게 했다. 카운터를 100 으로 올려 나이 기준 10 을
     * 넘기면 3 번이 재고로 돌아와야 한다. 2026-10-12 U-3 의 결번 42 가 이 모양이었다.
     */
    @Test
    void 주인_잃은_번호를_재고로_되돌린다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 2);
        발급행을_심는다(9103, 4);
        redisTemplate.opsForValue().set(COUNTER, "100");

        sut.sweep();

        assertThat(redisTemplate.opsForZSet().range(FREE, 0, -1)).containsExactly("3");
        // 점수가 번호라야 순번 확보의 ZPOPMIN 이 낮은 번호부터 꺼낸다
        assertThat(redisTemplate.opsForZSet().score(FREE, "3")).isEqualTo(3.0);
    }

    /*
     * 이 시험이 가장 위험한 자리를 막는다.
     *
     * 누군가 쥔 번호를 되살리면 같은 번호가 두 사람에게 나가고 한쪽이 uk_mc_coupon_seq 에 걸려
     * 그 요청이 실패한다. 쥐었다는 것은 seq 해시에 값으로 들어 있다는 뜻이다.
     */
    @Test
    void 누군가_쥔_번호는_되돌리지_않는다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 4);
        redisTemplate.opsForValue().set(COUNTER, "100");
        // 2 번과 3 번을 쥔 회원이 있다. 아직 행이 안 됐을 뿐이다
        redisTemplate.opsForHash().put(SEQ, "9201", "2");
        redisTemplate.opsForHash().put(SEQ, "9202", "3");

        sut.sweep();

        assertThat(redisTemplate.hasKey(FREE)).isFalse();
    }

    /*
     * 확정 표시가 붙은 값도 쥔 것으로 본다.
     *
     * 그 번호는 DB 행이 있어 후보에 안 들어오지만, 복구가 방금 세운 직후처럼 양쪽이 어긋나는
     * 순간이 있다. 표시를 떼고 보지 않으면 "2:1" 이 번호 2 와 안 맞아 그 번호를 되살린다.
     */
    @Test
    void 확정_표시가_붙은_번호도_쥔_것으로_본다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 4);
        redisTemplate.opsForValue().set(COUNTER, "100");
        redisTemplate.opsForHash().put(SEQ, "9201", "2:1");
        redisTemplate.opsForHash().put(SEQ, "9202", "3:1");

        sut.sweep();

        assertThat(redisTemplate.hasKey(FREE)).isFalse();
    }

    /*
     * 카운터에 가까운 번호는 아직 큐에 떠 있는 것이다.
     * 되살리면 살아 있는 티켓의 번호를 남에게 내준다.
     */
    @Test
    void 카운터에_가까운_번호는_안_되돌린다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 20);
        // 카운터 25 에 기준 10 이면 15 보다 큰 번호는 후보가 아니다. 결번 2..19 중 16..19 가 빠진다
        redisTemplate.opsForValue().set(COUNTER, "25");

        sut.sweep();

        assertThat(redisTemplate.opsForZSet().range(FREE, 0, -1))
                .contains("2", "15")
                .doesNotContain("16", "17", "18", "19");
    }

    // 두 번 돌아도 결과가 같다. NX 로 넣어 멱등이다
    @Test
    void 여러_번_돌아도_결과가_같다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 4);
        redisTemplate.opsForValue().set(COUNTER, "100");

        sut.sweep();
        sut.sweep();
        sut.sweep();

        assertThat(redisTemplate.opsForZSet().range(FREE, 0, -1)).containsExactly("2", "3");
    }

    /*
     * 넷의 수명은 counter 가 들고 나머지가 따라간다.
     * 이 스크립트도 free 를 만드는 자리라, 안 물려주면 넷 중 이 키만 이벤트보다 오래 산다.
     */
    @Test
    void 되돌린_키가_만료를_물려받는다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 4);
        redisTemplate.opsForValue().set(COUNTER, "100");
        redisTemplate.expire(COUNTER, Duration.ofMinutes(10));

        sut.sweep();

        assertThat(redisTemplate.getExpire(FREE)).isPositive();
    }

    // 카운터가 없으면 이벤트가 아니다. 안 열린 쿠폰에 번호를 넣으면 그것이 먼저 나간다
    @Test
    void 카운터가_없으면_아무것도_안_한다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 4);

        sut.sweep();

        assertThat(redisTemplate.hasKey(FREE)).isFalse();
    }

    // 결번이 없으면 넣을 것이 없다. 개수와 최댓값이 같은 경우다
    @Test
    void 결번이_없으면_아무것도_안_한다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 2);
        redisTemplate.opsForValue().set(COUNTER, "100");

        sut.sweep();

        assertThat(redisTemplate.hasKey(FREE)).isFalse();
    }

    /*
     * issue_limit 은 그 시점의 total_quantity 를 그대로 넣는다.
     * chk_mc_issue_seq 가 순번을 그 값과 견주므로 둘이 어긋나면 행이 안 들어간다.
     */
    private void 발급행을_심는다(long memberId, int issueSeq) {
        jdbcTemplate.update("""
                INSERT INTO member_coupon
                    (coupon_id, member_id, scope, issue_limit, issue_seq, status, issued_at, created_at, updated_at)
                VALUES (?, ?, 'ORDER',
                        (SELECT total_quantity FROM coupon WHERE coupon_id = ?),
                        ?, 'ISSUED', NOW(6), NOW(6), NOW(6))
                """, COUPON_ID, memberId, COUPON_ID, issueSeq);
    }
}
