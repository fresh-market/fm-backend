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
import org.springframework.test.context.jdbc.Sql;

/*
 * Redis 가 이벤트의 키를 잃었을 때 다시 세우는 경로를 실제 Redis 와 MySQL 로 본다.
 *
 * 단위 시험으로는 이 경로를 못 본다. 확인할 것이 "어느 키에 무엇이 들어갔나" 이고, 그것을 목으로
 * 재면 목에게 시킨 것을 그대로 돌려받는 것뿐이다. 특히 해시 값의 모양은 뒤에 순번 확보
 * 스크립트가 그대로 읽어 가는 값이라 실물로 봐야 한다.
 *
 * 기다림을 줄이는 설정이 없다. 복구가 더 이상 아무도 기다리지 않는다
 * (docs/coupon/rebuild-rearchitecture-2026-10-12.md 5.2).
 */
@SpringBootTest
@Sql("/sql/coupon-issue-fixture.sql")
class CouponSeqRebuildIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;

    private static final String SEQ = "coupon:9001:seq";
    private static final String FREE = "coupon:9001:free";
    private static final String COUNTER = "coupon:9001:counter";
    private static final String PENDING = "coupon:9001:pending";

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
        redisTemplate.delete(List.of(SEQ, FREE, COUNTER, PENDING));
        marks.forget(COUPON_ID);
    }

    /*
     * 이 시험이 복구의 전부를 한 번에 본다.
     *
     * 발급 행을 1, 2, 4 로 심어 3 번이 결번이 되게 했다. 옛 설계는 그 3 번을 free 로 되살렸는데
     * 그 계산이 전역 시야를 요구해 온라인에서 뺐다. 그래서 여기서는 free 가 비어 있어야 한다.
     */
    @Test
    void 키가_사라지면_DB_에서_다시_세운다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 2);
        발급행을_심는다(9103, 4);

        sut.rebuild(COUPON_ID);

        // 카운터는 마지막으로 나간 번호다. 다음 INCR 이 5 를 준다
        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("4");

        // 행이 있다는 것은 커밋까지 끝났다는 뜻이라 확정 표시가 붙는다
        Map<Object, Object> seq = redisTemplate.opsForHash().entries(SEQ);
        assertThat(seq).containsOnly(
                Map.entry("9101", "1:1"),
                Map.entry("9102", "2:1"),
                Map.entry("9103", "4:1"));

        // 큐가 비었으면 pending 도 비어 있다. 커밋된 회원은 미확정이 아니다
        assertThat(redisTemplate.hasKey(PENDING)).isFalse();
    }

    /*
     * free 를 안 세운다. 그것만 전역 시야를 요구하므로 온라인 밖으로 냈다.
     *
     * 1..최댓값에서 DB 와 모든 큐를 빼야 하는데, 내 큐만 빼고 세우면 남이 쥔 번호를 되살려 같은
     * 번호가 두 번 나간다. 2026-10-10 J-4 의 결번 180 이 그 모양이었다.
     *
     * 대가로 주인을 잃은 번호가 배치까지 결번으로 남는다. 설계 문서 8.2 가 그 값을 적어 두었다.
     */
    @Test
    void 결번을_free_로_되살리지_않는다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 4);

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.hasKey(FREE)).isFalse();
    }

    /*
     * 내 기억이 DB 보다 높으면 그쪽으로 세운다.
     *
     * DB 에는 커밋된 것만 있고 내 기억에는 아직 큐에 떠 있는 번호까지 있다. 낮은 쪽으로 세우면
     * 그 번호들이 다시 나가고 한쪽이 uk_mc_coupon_seq 에 걸려 결번이 된다.
     *
     * 이 항이 옛 설계의 "남의 큐를 모아 세기" 를 대신한다. 내가 쥔 것은 내가 알고 남이 쥔 것은
     * 남이 아는데, 카운터가 올리기만 하는 값이라 각자 올려도 전체 최댓값으로 수렴한다.
     */
    @Test
    void 내_기억이_DB_보다_높으면_그것으로_세운다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 2);
        marks.saw(COUPON_ID, 5);

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("5");
    }

    // 넷의 수명은 counter 가 들고 나머지가 따라간다. 복구는 그 물려받기 경로를 안 지나므로 직접 건다
    @Test
    void 세운_키들이_만료를_갖는다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 3);

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.getExpire(COUNTER)).isPositive();
        assertThat(redisTemplate.getExpire(SEQ)).isPositive();
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
     * 카운터가 있어도 복구가 돈다. 다만 그 값을 내리지 않는다.
     *
     * 존재를 관문으로 두면 승격 경로가 영영 안 걸린다. 복제가 밀린 채 승격되면 카운터는 살아
     * 있고 값만 뒤로 가기 때문이다.
     *
     * 그래서 막아야 하는 것은 복구가 도는 것이 아니라 복구가 낮은 값을 쓰는 것이다. 여기서는
     * 카운터가 77 인데 복구가 센 최대 순번이 1 이므로, coupon-open-gate.lua 가 77 을 지켜야 한다.
     *
     * 이 성질이 락을 없앤 근거다. 낮은 값이 높은 값을 덮을 수 없으면 여러 대가 동시에 돌아도 된다.
     */
    @Test
    void 카운터를_내리지_않는다() {
        발급행을_심는다(9101, 1);
        redisTemplate.opsForValue().set(COUNTER, "77");

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("77");

        // 복구는 돌았다. 커밋된 회원을 다시 세우는 것이 이 절차의 일이다
        assertThat(redisTemplate.opsForHash().get(SEQ, "9101")).isEqualTo("1:1");
    }

    // 카운터가 복구가 센 값보다 낮으면 올린다. 승격으로 뒤로 간 카운터를 되돌리는 경로다
    @Test
    void 카운터가_낮으면_올린다() {
        발급행을_심는다(9101, 50);
        redisTemplate.opsForValue().set(COUNTER, "7");

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("50");
    }

    /*
     * 두 번 돌아도 결과가 같다. 멱등이 이 설계의 요점이다.
     *
     * 옛 설계는 한 장애에 여러 번 도는 것이 결함이었다. 재연결 감시가 인스턴스마다 독립으로
     * 띄우는데 락은 동시 실행만 막아, 앞 재건이 끝나면 다음 인스턴스가 또 주도자가 됐다.
     * 지금은 여러 번 도는 것이 정상이고 오히려 수렴을 돕는다.
     */
    @Test
    void 여러_번_돌아도_결과가_같다() {
        발급행을_심는다(9101, 1);
        발급행을_심는다(9102, 4);

        sut.rebuild(COUPON_ID);
        String 한_번 = redisTemplate.opsForValue().get(COUNTER);
        sut.rebuild(COUPON_ID);
        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo(한_번).isEqualTo("4");
        assertThat(redisTemplate.opsForHash().entries(SEQ)).containsOnly(
                Map.entry("9101", "1:1"),
                Map.entry("9102", "4:1"));
    }

    /*
     * 관리자가 방금 다시 준비한 이벤트는 건드리지 않는다.
     *
     * 준비 단계가 카운터를 0 으로 세운다. 복구가 세울 값은 지난 이벤트의 최댓값이라, 덮으면
     * 그만큼의 재고를 통째로 버린다. 만 장짜리에서 5,887 이면 4,113장만 나간다.
     */
    @Test
    void 방금_다시_준비한_이벤트는_덮지_않는다() {
        발급행을_심는다(9101, 50);
        redisTemplate.opsForValue().set(COUNTER, "0");

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("0");
        assertThat(redisTemplate.hasKey(SEQ)).isFalse();
    }

    /*
     * 카운터를 올리는 쓰기가 만료를 지우지 않는다.
     *
     * 카운터가 네 키의 수명을 들고 있어서, 그것이 사라지면 넷이 이벤트보다 오래 산다. 뒤이어
     * 도는 수명 물려주기도 물려줄 값을 못 찾아 free 에만 만료가 영영 안 붙는다.
     *
     * 전손 복구는 뒤에서 applyTtl 로 다시 거니까 그 경로만 보면 안 드러난다. 뒤처짐에서 카운터만
     * 올리는 경로가 그 복원을 안 지나므로 거기서 샌다. 그래서 스크립트가 KEEPTTL 로 지킨다.
     */
    @Test
    void 카운터를_올려도_만료가_남는다() {
        발급행을_심는다(9101, 50);
        redisTemplate.opsForValue().set(COUNTER, "7");
        redisTemplate.expire(COUNTER, java.time.Duration.ofMinutes(10));

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.opsForValue().get(COUNTER)).isEqualTo("50");
        assertThat(redisTemplate.getExpire(COUNTER)).isPositive();
    }

    // 락을 안 잡는다. 잡으면 다음 손실을 그 TTL 만큼 못 고친다
    @Test
    void 락_키를_만들지_않는다() {
        발급행을_심는다(9101, 1);

        sut.rebuild(COUPON_ID);

        assertThat(redisTemplate.hasKey("coupon:9001:rebuild")).isFalse();
        assertThat(redisTemplate.hasKey("coupon:9001:rebuild:done")).isFalse();
        assertThat(redisTemplate.hasKey("coupon:9001:rebuild:queued")).isFalse();
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
