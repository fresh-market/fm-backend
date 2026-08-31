package com.freshmarket.coupon.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.coupon.internal.exception.CouponErrorCode;
import com.freshmarket.coupon.internal.service.MemberCouponStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/*
 * 상태 전이가 실제 MySQL 에서 한 번만 반영되는지 본다.
 *
 * 요구사항이 "반복해서, 또는 동시에 발생해도 한 번만 반영" 을 요구하는데, 동시 요청이 실제로
 * 하나로 줄어드는지는 단위 테스트로 확인할 수 없다. 행 락이 하는 일이라 DB 가 있어야 드러난다.
 */
@SpringBootTest
@Sql("/sql/coupon-issue-fixture.sql")
class MemberCouponStatusIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;
    private static final long MEMBER_ID = 9101L;

    @Autowired
    private MemberCouponStatusService sut;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long memberCouponId;

    @BeforeEach
    void 발급분을_하나_만든다() {
        jdbcTemplate.update("""
                INSERT INTO member_coupon
                    (coupon_id, member_id, scope, issue_limit, issue_seq, status, issued_at, created_at, updated_at)
                VALUES (?, ?, 'ORDER', 100, 1, 'ISSUED', NOW(6), NOW(6), NOW(6))
                """, COUPON_ID, MEMBER_ID);
        memberCouponId = jdbcTemplate.queryForObject(
                "SELECT member_coupon_id FROM member_coupon WHERE coupon_id = ? AND member_id = ?",
                Long.class, COUPON_ID, MEMBER_ID);
    }

    @Test
    void 사용하면_상태와_이력이_함께_바뀐다() {
        sut.use(memberCouponId, MEMBER_ID);

        assertThat(status()).isEqualTo("USED");
        assertThat(usedAt()).isNotNull();
        assertThat(historyCount()).isEqualTo(1);
    }

    // 같은 요청이 두 번 와도 이력이 두 줄이 되면 안 된다
    @Test
    void 두_번_사용해도_한_번만_반영된다() {
        sut.use(memberCouponId, MEMBER_ID);
        sut.use(memberCouponId, MEMBER_ID);

        assertThat(status()).isEqualTo("USED");
        assertThat(historyCount()).isEqualTo(1);
    }

    /*
     * 이 시험이 이 작업의 값이다.
     * 조건부 UPDATE 가 겹친 요청을 하나로 줄이는 것은 행 락이 하는 일이라 DB 없이는 안 드러난다.
     */
    @Test
    void 동시에_사용해도_한_번만_반영된다() throws Exception {
        int 요청_수 = 20;
        CountDownLatch 출발 = new CountDownLatch(1);
        CountDownLatch 도착 = new CountDownLatch(요청_수);
        AtomicInteger 성공 = new AtomicInteger();

        try (ExecutorService 스레드들 = Executors.newFixedThreadPool(요청_수)) {
            for (int i = 0; i < 요청_수; i++) {
                스레드들.submit(() -> {
                    try {
                        출발.await();
                        sut.use(memberCouponId, MEMBER_ID);
                        성공.incrementAndGet();
                    } catch (Exception ignored) {
                        // 겹친 요청 중 진 쪽이다. 여기서 볼 것이 없다
                    } finally {
                        도착.countDown();
                    }
                });
            }
            출발.countDown();
            assertThat(도착.await(30, TimeUnit.SECONDS)).isTrue();
        }

        // 스물이 다 성공으로 답한다. 멱등이라 늦게 온 것도 실패가 아니다
        assertThat(성공.get()).isEqualTo(요청_수);
        // 그래도 실제로 바뀐 것은 한 번이다
        assertThat(historyCount()).isEqualTo(1);
        assertThat(status()).isEqualTo("USED");
    }

    /*
     * 사용을 철회하면 used_at 도 함께 비워야 한다.
     * chk_mc_used_at 이 사용 상태와 사용 시각을 묶고 있어, 안 비우면 갱신 자체가 제약에 걸린다.
     */
    @Test
    void 철회하면_사용_시각이_비워진다() {
        sut.use(memberCouponId, MEMBER_ID);

        sut.cancelUse(memberCouponId, MEMBER_ID);

        assertThat(status()).isEqualTo("CANCELED");
        assertThat(usedAt()).isNull();
        assertThat(historyCount()).isEqualTo(2);
    }

    /*
     * 만료 배치가 아직 안 돌아 표시가 ISSUED 로 남아 있는 창이다.
     * 상태만 조건에 넣으면 이 창에서 만료된 쿠폰이 쓰인다.
     */
    @Test
    void 기간이_지났으면_표시가_ISSUED_여도_못_쓴다() {
        기간을_넘긴다();

        assertThatThrownBy(() -> sut.use(memberCouponId, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.NOT_USABLE_PERIOD);

        assertThat(status()).isEqualTo("ISSUED");
        assertThat(historyCount()).isZero();
    }

    // 주문 취소로 돌려받은 쿠폰은 기간이 남았으면 다시 쓸 수 있다
    @Test
    void 철회한_쿠폰을_다시_쓴다() {
        sut.use(memberCouponId, MEMBER_ID);
        sut.cancelUse(memberCouponId, MEMBER_ID);

        sut.use(memberCouponId, MEMBER_ID);

        assertThat(status()).isEqualTo("USED");
        assertThat(historyCount()).isEqualTo(3);
        assertThat(마지막_전이()).isEqualTo("CANCELED");
    }

    // 주문 취소는 쿠폰 기간이 지난 뒤에도 일어난다. 여기에 기간을 걸면 쿠폰을 영영 못 돌려준다
    @Test
    void 기간이_지나도_사용을_철회할_수_있다() {
        sut.use(memberCouponId, MEMBER_ID);
        기간을_넘긴다();

        sut.cancelUse(memberCouponId, MEMBER_ID);

        assertThat(status()).isEqualTo("CANCELED");
    }

    @Test
    void 쓰지_않은_것은_철회할_수_없다() {
        assertThatThrownBy(() -> sut.cancelUse(memberCouponId, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.INVALID_STATUS_TRANSITION);
    }

    // 남의 발급분 번호를 실어 보내도 없는 것과 같은 실패가 되어야 존재를 알아낼 수 없다
    @Test
    void 남의_발급분은_없는_것과_같다() {
        assertThatThrownBy(() -> sut.use(memberCouponId, 9102L))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.MEMBER_COUPON_NOT_FOUND);
    }

    @Test
    void 만료가_상태와_이력을_함께_바꾼다() {
        기간을_넘긴다();

        // 만료 배치는 이 시험의 행만 보지 않는다. 다른 시험이 남긴 행까지 세므로 개수를 못 박지 않는다
        assertThat(sut.expireOverdueChunk()).isPositive();

        assertThat(status()).isEqualTo("EXPIRED");
        assertThat(historyCount()).isEqualTo(1);
    }

    /*
     * 유효기간은 coupon 이 갖는다. V30 이 발급 시점 복사본을 걷어냈다.
     * chk_coupon_valid_period 가 시작일과 종료일의 순서를 묶고 있어 둘을 함께 옮긴다.
     */
    private void 기간을_넘긴다() {
        jdbcTemplate.update("""
                UPDATE coupon SET valid_from = '2019-01-01', valid_to = '2020-01-01'
                 WHERE coupon_id = ?
                """, COUPON_ID);
    }

    private String 마지막_전이() {
        return jdbcTemplate.queryForObject("""
                SELECT from_status FROM member_coupon_status_history
                 WHERE member_coupon_id = ?
                 ORDER BY member_coupon_status_history_id DESC LIMIT 1
                """, String.class, memberCouponId);
    }

    private String status() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM member_coupon WHERE member_coupon_id = ?", String.class, memberCouponId);
    }

    private Object usedAt() {
        return jdbcTemplate.queryForObject(
                "SELECT used_at FROM member_coupon WHERE member_coupon_id = ?", Object.class, memberCouponId);
    }

    private Integer historyCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_coupon_status_history WHERE member_coupon_id = ?",
                Integer.class, memberCouponId);
    }
}
