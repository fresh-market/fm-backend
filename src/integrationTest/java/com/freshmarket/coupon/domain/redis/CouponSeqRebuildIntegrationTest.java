package com.freshmarket.coupon.domain.redis;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Redis 가 이벤트의 키를 잃었을 때 DB 로부터 다시 세우는 경로를 실제 Redis 와 MySQL 로 본다.
 *
 * 단위 시험으로는 이 경로를 못 본다. 확인할 것이 "어느 키에 무엇이 들어갔나" 이고, 그것을 목으로
 * 재면 목에게 시킨 것을 그대로 돌려받는 것뿐이다. 특히 정렬집합의 점수와 해시 값의 모양은 뒤에
 * 순번 확보 스크립트가 그대로 읽어 가는 값이라 실물로 봐야 한다.
 *
 * 조용해지기를 기다리는 시간을 500밀리초로 줄인다. 운영값은 60초라 그대로 두면 이 시험 하나가
 * 회차마다 1분을 먹는다. 재는 것은 기다림의 길이가 아니라 기다린 뒤에 세워지는 값이다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "coupon.issue.commit-wait=200ms",
        "coupon.issue.reclaim-after=500ms"
})
@Sql("/sql/coupon-issue-fixture.sql")
class CouponSeqRebuildIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;

    private static final String SEQ = "coupon:9001:seq";
    private static final String FREE = "coupon:9001:free";
    private static final String COUNTER = "coupon:9001:counter";
    private static final String PENDING = "coupon:9001:pending";
    private static final String REBUILD_LOCK = "coupon:9001:rebuild";

    @Autowired
    private CouponSeqRebuilder sut;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 승격 직후를 흉내 낸다. 네 키가 통째로 없는 상태다
    @BeforeEach
    void 키를_모두_지운다() {
        redisTemplate.delete(List.of(SEQ, FREE, COUNTER, PENDING, REBUILD_LOCK));
    }

    /*
     * 이 시험이 재건의 전부를 한 번에 본다.
     * 발급 행을 1, 2, 4 로 심어 3 번이 구멍이 되게 했다. 그 구멍이 free 로 살아나야 재고가
     * 덜 팔리지 않는다.
     */
    @Test
    void 키가_사라지면_DB_에서_다시_세운다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 2);
        발급행을_심는다(9103, 4);

        sut.rebuildIfLost(COUPON_ID);

        // 카운터는 마지막으로 나간 번호다. 다음 INCR 이 5 를 준다
        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("4");

        // 행이 있다는 것은 커밋까지 끝났다는 뜻이라 확정 표시가 붙는다
        Map<Object, Object> seq = redisTemplate.opsForHash().entries(SEQ);
        assertThat(seq).containsOnly(
                Map.entry("9101", "1:1"),
                Map.entry("9102", "2:1"),
                Map.entry("9103", "4:1"));

        // 3 번은 아무도 안 가진 번호다. 되살려야 한다
        assertThat(redisTemplate.opsForZSet().range(FREE, 0, -1)).containsExactly("3");
        assertThat(redisTemplate.opsForZSet().score(FREE, "3")).isEqualTo(3.0);

        // pending 은 세우지 않는다. DB 에 행이 없는 회원들이라 복원할 근거가 없다
        assertThat(redisTemplate.hasKey(PENDING)).isFalse();
    }

    // 넷의 수명은 counter 가 들고 나머지가 따라간다. 재건은 그 물려받기 경로를 안 지나므로 직접 건다
    @Test
    void 세운_키들이_만료를_갖는다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 3);

        sut.rebuildIfLost(COUPON_ID);

        assertThat(redisTemplate.getExpire(COUNTER)).isPositive();
        assertThat(redisTemplate.getExpire(SEQ)).isPositive();
        assertThat(redisTemplate.getExpire(FREE)).isPositive();
    }

    // 발급이 하나도 없었으면 이벤트를 여는 prepare 가 세우는 값과 같아진다
    @Test
    void 발급이_없었으면_카운터가_0_이다() {
        sut.rebuildIfLost(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("0");
        assertThat(redisTemplate.hasKey(FREE)).isFalse();
    }

    /*
     * -2 는 손실만 뜻하지 않는다. 관리자가 아직 안 연 이벤트도 같은 값을 낸다.
     * 그것을 손실로 보고 카운터를 세우면 열지도 않은 이벤트가 발급을 시작한다.
     */
    @Test
    void 꺼진_이벤트는_세우지_않는다() {
        jdbcTemplate.update("UPDATE coupon SET is_active = FALSE WHERE coupon_id = ?", COUPON_ID);

        sut.rebuildIfLost(COUPON_ID);

        assertThat(redisTemplate.hasKey(COUNTER)).isFalse();
    }

    // 카운터가 있으면 손실이 아니다. 덮으면 돌고 있는 이벤트의 번호가 뒤로 밀린다
    @Test
    void 카운터가_있으면_건드리지_않는다() {
        발급행을_심는다(9101, 1);
        redisTemplate.opsForValue().set(COUNTER, "77");

        sut.rebuildIfLost(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("77");
        assertThat(redisTemplate.hasKey(SEQ)).isFalse();
    }

    // 다른 인스턴스가 재건 중이면 비켜선다. 둘이 같이 쓰면 절반씩 채운 상태가 된다
    @Test
    void 남이_재건_중이면_비켜선다() {
        발급행을_심는다(9101, 1);
        redisTemplate.opsForValue().set(REBUILD_LOCK, "다른-인스턴스");

        sut.rebuildIfLost(COUPON_ID);

        assertThat(redisTemplate.hasKey(COUNTER)).isFalse();
    }

    // 재건이 끝나면 락을 돌려놓는다. 안 그러면 다음 손실을 락 TTL 만큼 못 고친다
    @Test
    void 재건이_끝나면_락을_푼다() {
        발급행을_심는다(9101, 1);

        sut.rebuildIfLost(COUPON_ID);

        assertThat(redisTemplate.hasKey(REBUILD_LOCK)).isFalse();
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
