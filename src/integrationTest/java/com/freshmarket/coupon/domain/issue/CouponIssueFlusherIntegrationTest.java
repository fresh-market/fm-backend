package com.freshmarket.coupon.domain.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.coupon.domain.entity.CouponScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/*
 * 큐에 넣은 것이 행이 되고 그 결과가 요청 스레드에게 돌아오는지 본다.
 *
 * 플러시 스레드는 컨텍스트가 뜨면서 이미 돌고 있다. 그래서 여기서는 넣고 future 를 기다릴 뿐이고,
 * 그것이 실제 운영에서 요청 스레드가 하는 일과 같다.
 */
@SpringBootTest
@Sql("/sql/coupon-issue-fixture.sql")
class CouponIssueFlusherIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;
    private static final int ISSUE_LIMIT = 100;
    private static final long AWAIT_SECONDS = 10;

    private static final String SEQ = "coupon:9001:seq";
    private static final String FREE = "coupon:9001:free";
    private static final String PENDING = "coupon:9001:pending";

    @Autowired
    private CouponIssueQueue queue;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 키를_비운다() {
        redisTemplate.delete(List.of(SEQ, FREE, "coupon:9001:counter", PENDING));
    }

    @Test
    void 큐에_넣으면_행이_되고_결과가_돌아온다() throws Exception {
        IssueTicket ticket = 순번을_받은_요청(9101L, 1);

        IssueOutcome outcome = 결과를_기다린다(ticket);

        assertThat(outcome).isEqualTo(new IssueOutcome.Issued(1));
        assertThat(발급된_순번(9101L)).isEqualTo(1);
    }

    @Test
    void 한_배치가_여러_건을_함께_쓴다() throws Exception {
        IssueTicket first = 순번을_받은_요청(9101L, 1);
        IssueTicket second = 순번을_받은_요청(9102L, 2);
        IssueTicket third = 순번을_받은_요청(9103L, 3);

        assertThat(결과를_기다린다(first)).isEqualTo(new IssueOutcome.Issued(1));
        assertThat(결과를_기다린다(second)).isEqualTo(new IssueOutcome.Issued(2));
        assertThat(결과를_기다린다(third)).isEqualTo(new IssueOutcome.Issued(3));

        assertThat(발급된_행_수()).isEqualTo(3);
    }

    /*
     * 커밋 뒤에 확정 표시가 붙고 미확정 목록에서 빠지는지 본다.
     * 이 표시가 있어야 그 회원의 재시도가 DB 까지 안 가고 Redis 에서 끝난다.
     */
    @Test
    void 커밋한_뒤_확정_표시를_남긴다() throws Exception {
        IssueTicket ticket = 순번을_받은_요청(9101L, 1);
        결과를_기다린다(ticket);

        assertThat(redisTemplate.opsForHash().get(SEQ, "9101")).isEqualTo("1:1");
        assertThat(redisTemplate.opsForZSet().score(PENDING, "9101")).isNull();
    }

    /*
     * uk_mc_coupon_member 다. Redis 가 매핑을 잃은 뒤에 다시 온 회원에게 생긴다.
     * 이번에 받은 번호는 아무도 안 썼으므로 반납하고, 매핑은 원래 갖고 있던 순번으로 고친다.
     */
    @Test
    void 이미_가진_회원이면_번호를_반납하고_매핑을_고친다() throws Exception {
        결과를_기다린다(순번을_받은_요청(9101L, 1));
        redisTemplate.opsForHash().delete(SEQ, "9101");

        IssueTicket again = 순번을_받은_요청(9101L, 7);
        IssueOutcome outcome = 결과를_기다린다(again);

        assertThat(outcome).isEqualTo(new IssueOutcome.AlreadyIssued(1));
        assertThat(redisTemplate.opsForZSet().score(FREE, "7")).isEqualTo(7.0);
        assertThat(redisTemplate.opsForHash().get(SEQ, "9101")).isEqualTo("1:1");
        assertThat(발급된_행_수()).isEqualTo(1);
    }

    /*
     * uk_mc_coupon_seq 다. 그 번호는 남이 쓰고 있으므로 반납하면 안 된다.
     * 반납하면 또 다른 회원이 그 번호를 받아 같은 충돌을 되풀이한다.
     */
    @Test
    void 남이_쓰는_번호면_반납하지_않고_매핑만_지운다() throws Exception {
        결과를_기다린다(순번을_받은_요청(9101L, 1));

        IssueTicket collided = 순번을_받은_요청(9102L, 1);
        IssueOutcome outcome = 결과를_기다린다(collided);

        assertThat(outcome).isInstanceOf(IssueOutcome.Congested.class);
        assertThat(redisTemplate.opsForZSet().score(FREE, "1")).isNull();
        assertThat(redisTemplate.opsForHash().get(SEQ, "9102")).isNull();
        assertThat(발급된_행_수()).isEqualTo(1);
    }

    /*
     * 순번을 받은 요청이 큐에 들어가는 것까지를 흉내 낸다.
     * 순번 확보는 CouponSeqAllocatorIntegrationTest 가 따로 보므로 여기서는 번호를 직접 준다.
     */
    private IssueTicket 순번을_받은_요청(long memberId, int seq) {
        redisTemplate.opsForHash().put(SEQ, String.valueOf(memberId), String.valueOf(seq));
        redisTemplate.opsForZSet().add(PENDING, String.valueOf(memberId), System.currentTimeMillis());

        IssueTicket ticket = IssueTicket.of(COUPON_ID, memberId, CouponScope.ORDER, ISSUE_LIMIT, seq);
        queue.submit(ticket);
        return ticket;
    }

    private IssueOutcome 결과를_기다린다(IssueTicket ticket) throws Exception {
        return ticket.future().get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    private Integer 발급된_순번(long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT issue_seq FROM member_coupon WHERE coupon_id = ? AND member_id = ?",
                Integer.class, COUPON_ID, memberId);
    }

    private Integer 발급된_행_수() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_coupon WHERE coupon_id = ?", Integer.class, COUPON_ID);
    }
}
