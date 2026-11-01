package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
 * 재건을 실제 Redis 와 MySQL 로 본다.
 *
 * 단위 시험으로는 이 경로를 못 본다. 확인할 것이 "어느 키에 무엇이 들어갔나" 이고, 그것을 목으로
 * 재면 목에게 시킨 것을 그대로 돌려받는 것뿐이다. 특히 free 를 쓰는 스크립트가 seq 해시를 통째로
 * 읽어 쥔 번호를 가리는 동작과 해시 값의 모양은 실물로만 확인된다.
 *
 * 나이 기준을 10 으로 줄인다. 재는 것은 그 값의 크기가 아니라 그 조건이 거는지다.
 */
@SpringBootTest
@TestPropertySource(properties = "coupon.issue.free-margin=10")
@Sql("/sql/coupon-issue-fixture.sql")
class CouponSeqRebuildIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;

    private static final String SEQ = "coupon:9001:seq";
    private static final String FREE = "coupon:9001:free";
    private static final String COUNTER = "coupon:9001:counter";
    private static final String PENDING = "coupon:9001:pending";
    private static final String LOCK = "coupon:9001:rebuild";

    @Autowired
    private CouponSeqRebuilder sut;

    @Autowired
    private CouponSeqMarks marks;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 전손을 흉내 낸다. 네 키가 통째로 없는 상태다
    @BeforeEach
    void 키를_모두_지운다() {
        redisTemplate.delete(List.of(SEQ, FREE, COUNTER, PENDING, LOCK));
        marks.forget(COUPON_ID);
        jdbcTemplate.update("DELETE FROM member_coupon WHERE coupon_id = ?", COUPON_ID);
    }

    /*
     * 이 시험이 재건의 전부를 한 번에 본다.
     *
     * 발급 행을 1, 2, 4 로 심어 3 번이 결번이 되게 했다. 카운터를 100 으로 올려 나이 기준 10 을
     * 넘기면 3 번이 재고로 돌아와야 한다.
     */
    @Test
    void 키가_사라지면_DB_에서_다시_세운다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 2);
        발급행을_심는다(9103, 4);
        marks.saw(COUPON_ID, 100);

        sut.rebuild(COUPON_ID);

        // 카운터는 기억과 DB 최댓값 중 큰 쪽이다
        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("100");

        // 행이 있다는 것은 커밋까지 끝났다는 뜻이라 확정 표시가 붙는다
        assertThat(redisTemplate.opsForHash().entries(SEQ)).containsOnly(
                Map.entry("9101", "1:1"),
                Map.entry("9102", "2:1"),
                Map.entry("9103", "4:1"));

        // 3 번은 아무도 안 가진 번호다. 되살려야 한다
        assertThat(redisTemplate.opsForZSet().range(FREE, 0, -1)).containsExactly("3");
        assertThat(redisTemplate.opsForZSet().score(FREE, "3")).isEqualTo(3.0);
    }

    /*
     * 이 시험이 가장 위험한 자리를 막는다.
     *
     * 누군가 쥔 번호를 되살리면 같은 번호가 두 사람에게 나가고 한쪽이 uk_mc_coupon_seq 에 걸려
     * 그 요청이 실패한다. 쥐었다는 것은 seq 해시에 값으로 들어 있다는 뜻이고, 그 가리기가 발급을
     * 멈추지 않아도 되는 근거다.
     */
    @Test
    void 누군가_쥔_번호는_되돌리지_않는다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 4);
        marks.saw(COUPON_ID, 100);
        // 2 번과 3 번을 쥔 회원이 있다. 아직 행이 안 됐을 뿐이다
        redisTemplate.opsForHash().put(SEQ, "9201", "2");
        redisTemplate.opsForHash().put(SEQ, "9202", "3");

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.hasKey(FREE)).isFalse();
    }

    /*
     * 확정 표시가 붙은 값도 쥔 것으로 본다.
     * 표시를 떼고 보지 않으면 "2:1" 이 번호 2 와 안 맞아 그 번호를 되살린다.
     */
    @Test
    void 확정_표시가_붙은_번호도_쥔_것으로_본다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 4);
        marks.saw(COUPON_ID, 100);
        redisTemplate.opsForHash().put(SEQ, "9201", "2:1");
        redisTemplate.opsForHash().put(SEQ, "9202", "3:1");

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.hasKey(FREE)).isFalse();
    }

    /*
     * 카운터에 가까운 번호는 아직 큐에 떠 있는 것이다.
     * 되살리면 살아 있는 티켓의 번호를 남에게 내주고, 아직 자기 큐를 못 되살린 대도 이 조건이 덮는다.
     */
    @Test
    void 카운터에_가까운_번호는_안_되돌린다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 20);
        // 카운터 25 에 기준 10 이면 15 보다 큰 번호는 후보가 아니다
        marks.saw(COUPON_ID, 25);

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForZSet().range(FREE, 0, -1))
                .contains("2", "15")
                .doesNotContain("16", "17", "18", "19");
    }

    /*
     * 카운터가 있어도 재건이 돈다. 다만 그 값을 내리지 않는다.
     *
     * 존재를 관문으로 두면 승격 경로가 영영 안 걸린다. 복제가 밀린 채 승격되면 카운터는 살아
     * 있고 값만 뒤로 가기 때문이다. 이 성질이 "각자 올려도 된다" 의 근거다.
     */
    @Test
    void 카운터를_내리지_않는다() {
        발급행을_심는다(9101, 1);
        redisTemplate.opsForValue().set(COUNTER, "77");

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("77");
        assertThat(redisTemplate.opsForHash().get(SEQ, "9101")).isEqualTo("1:1");
    }

    @Test
    void 카운터가_낮으면_올린다() {
        발급행을_심는다(9101, 50);
        redisTemplate.opsForValue().set(COUNTER, "7");

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("50");
    }

    /*
     * 카운터를 올리는 쓰기가 만료를 지우지 않는다.
     *
     * 카운터가 네 키의 수명을 들고 있어서, 그것이 사라지면 넷이 이벤트보다 오래 산다. 각 대가
     * 카운터만 올리는 경로는 applyTtl 복원을 안 지나므로 KEEPTTL 이 그것을 지킨다.
     */
    @Test
    void 카운터를_올려도_만료가_남는다() {
        발급행을_심는다(9101, 50);
        redisTemplate.opsForValue().set(COUNTER, "7");
        redisTemplate.expire(COUNTER, Duration.ofMinutes(10));

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("50");
        assertThat(redisTemplate.getExpire(COUNTER)).isPositive();
    }

    /*
     * 재건이 네 키에 수명을 건다.
     *
     * 전손이면 넷 다 재건이 만든다. 평상시에는 순번 확보가 카운터에서 물려받아 거는데 그
     * 경로를 안 지나므로, 여기서 안 걸면 넷이 이벤트보다 오래 산다.
     *
     * 예전에는 자바가 카운터에 EXPIREAT 을 걸고 그 값을 PTTL 로 되읽어 나머지 셋에 걸었다.
     * 왕복이 다섯이었고 그 사이에 재건이 죽으면 셋이 수명 없이 남았다. 지금은 카운터를 세우는
     * 스크립트가 넷을 한꺼번에 건다 (docs/coupon/rebuild-v5/design.md 9.1).
     */
    @Test
    void 네_키에_수명을_건다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 40);

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.getExpire(COUNTER)).isPositive();
        assertThat(redisTemplate.getExpire(SEQ)).isPositive();
        assertThat(redisTemplate.getExpire(FREE)).isPositive();
    }

    /*
     * 여러 번 돌아도 결과가 같다. 멱등이 이 설계의 요점이다.
     *
     * 재연결 감시가 인스턴스마다 독립으로 띄우므로 한 장애에 여러 번 돈다. 예전에는 그것이
     * 결함이었고 지금은 정상이다.
     */
    @Test
    void 여러_번_돌아도_결과가_같다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 4);
        marks.saw(COUPON_ID, 100);

        sut.rebuild(COUPON_ID);
        sut.rebuild(COUPON_ID);
        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("100");
        assertThat(redisTemplate.opsForZSet().range(FREE, 0, -1)).containsExactly("2", "3");
    }

    /*
     * 락을 못 잡아도 카운터와 자기 큐는 세운다.
     * 락은 선출일 뿐이고 발급을 막지 않으므로, 못 잡은 대가 그냥 돌아가면 그 기억이 안 반영된다.
     */
    @Test
    void 남이_free_를_세우는_중이어도_카운터는_올린다() {
        발급행을_심는다(9101, 1);
        marks.saw(COUPON_ID, 100);
        redisTemplate.opsForValue().set(LOCK, "다른-인스턴스");

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("100");
        // 남의 락은 안 건드린다
        assertThat(redisTemplate.opsForValue().get(LOCK)).isEqualTo("다른-인스턴스");
    }

    // 자기가 잡은 락은 끝나면 돌려놓는다
    @Test
    void 자기_락은_끝나면_푼다() {
        발급행을_심는다(9101, 1);
        marks.saw(COUPON_ID, 100);

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.hasKey(LOCK)).isFalse();
    }

    // 발급이 하나도 없었으면 이벤트를 여는 prepare 가 세우는 값과 같아진다
    @Test
    void 발급이_없었으면_카운터가_0_이다() {
        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("0");
        assertThat(redisTemplate.hasKey(SEQ)).isFalse();
    }

    /*
     * -2 는 손실만 뜻하지 않는다. 관리자가 아직 안 연 이벤트도 같은 값을 낸다.
     * 그것을 손실로 보고 카운터를 세우면 열지도 않은 이벤트가 발급을 시작한다.
     */
    @Test
    void 꺼진_이벤트는_세우지_않는다() {
        jdbcTemplate.update("UPDATE coupon SET is_active = FALSE WHERE coupon_id = ?", COUPON_ID);

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.hasKey(COUNTER)).isFalse();
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
