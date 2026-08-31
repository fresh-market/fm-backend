package com.freshmarket.coupon.internal.redis;

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
 * 남들이 큐를 올리기를 기다리는 시간을 200밀리초로 줄인다. 재는 것은 기다림의 길이가 아니라
 * 기다린 뒤에 세워지는 값이다.
 *
 * 다른 인스턴스의 큐는 재건용 해시에 직접 심어 흉내 낸다. 이 JVM 의 큐에 티켓을 넣으면 플러시
 * 스레드가 곧바로 가져가 버려서, 재건이 무엇을 봤는지가 회차마다 달라진다.
 */
@SpringBootTest
@TestPropertySource(properties = "coupon.issue.rebuild-contribute-wait=200ms")
@Sql("/sql/coupon-issue-fixture.sql")
class CouponSeqRebuildIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;

    private static final String SEQ = "coupon:9001:seq";
    private static final String FREE = "coupon:9001:free";
    private static final String COUNTER = "coupon:9001:counter";
    private static final String PENDING = "coupon:9001:pending";
    private static final String REBUILD_LOCK = "coupon:9001:rebuild";
    private static final String REBUILD_QUEUED = "coupon:9001:rebuild:queued";

    @Autowired
    private CouponSeqRebuilder sut;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 승격 직후를 흉내 낸다. 네 키가 통째로 없는 상태다
    @BeforeEach
    void 키를_모두_지운다() {
        redisTemplate.delete(List.of(SEQ, FREE, COUNTER, PENDING, REBUILD_LOCK, REBUILD_QUEUED));
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

        // 큐가 비었으면 pending 도 비어 있다. 커밋된 회원은 미확정이 아니다
        assertThat(redisTemplate.hasKey(PENDING)).isFalse();
    }

    /*
     * 큐에 떠 있는 번호가 재건에 반영되는지 본다.
     * 이것이 없으면 재건이 그 번호를 아무도 안 쥔 것으로 보고 남에게 다시 내준다.
     */
    @Test
    void 큐에_있는_번호는_되살아난다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 2);
        // 다른 인스턴스가 5 번을 쥐고 있다. 3 과 4 는 주인이 없다
        다른_인스턴스가_쥔다(9103, 5);

        sut.rebuildIfLost(COUPON_ID);

        // 카운터는 DB 의 최댓값이 아니라 큐까지 본 최댓값이다
        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("5");

        // 확정분에는 표시가 붙고 큐에서 온 것에는 안 붙는다. 붙이면 회수가 그 번호를 안 건드린다
        Map<Object, Object> seq = redisTemplate.opsForHash().entries(SEQ);
        assertThat(seq).containsOnly(
                Map.entry("9101", "1:1"),
                Map.entry("9102", "2:1"),
                Map.entry("9103", "5"));

        // 5 번은 주인이 있으므로 free 에 들어가면 안 된다
        assertThat(redisTemplate.opsForZSet().range(FREE, 0, -1)).containsExactly("3", "4");

        // 큐의 티켓이 곧 pending 의 정의다
        assertThat(redisTemplate.opsForZSet().range(PENDING, 0, -1)).containsExactly("9103");
    }

    // 재건용 해시는 다 쓰면 지운다. 남으면 다음 재건이 지난 회차의 큐를 보고 세운다
    @Test
    void 재건이_끝나면_올려_둔_큐를_지운다() {
        다른_인스턴스가_쥔다(9103, 1);

        sut.rebuildIfLost(COUPON_ID);

        assertThat(redisTemplate.hasKey(REBUILD_QUEUED)).isFalse();
    }

    /*
     * 확정된 매핑을 큐에서 온 것으로 덮으면 안 된다.
     * 덮으면 회수가 그 번호를 미확정으로 보고 남에게 넘겨 같은 번호가 두 번 나간다.
     */
    @Test
    void 커밋된_회원은_큐의_값으로_덮이지_않는다() {
        발급행을_심는다(9101, 1);
        // 플러시가 방금 커밋했는데 그 인스턴스의 스냅숏에는 아직 남아 있던 경우다
        다른_인스턴스가_쥔다(9101, 1);

        sut.rebuildIfLost(COUPON_ID);

        assertThat(redisTemplate.opsForHash().get(SEQ, "9101")).isEqualTo("1:1");
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

    // 다른 인스턴스가 자기 큐를 올린 것을 흉내 낸다. 회원 -> 순번이다
    private void 다른_인스턴스가_쥔다(long memberId, int issueSeq) {
        redisTemplate.opsForHash().put(REBUILD_QUEUED,
                String.valueOf(memberId), String.valueOf(issueSeq));
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
