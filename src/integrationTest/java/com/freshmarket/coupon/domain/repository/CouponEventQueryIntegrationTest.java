package com.freshmarket.coupon.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import com.freshmarket.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/*
 * 이벤트를 열고 닫는 조건부 SQL 을 실제 MySQL 로 검증한다.
 *
 * 조건이 SQL 안에 있어서 단위 테스트로는 무엇도 확인하지 못한다. 목이 돌려주는 숫자를 그대로
 * 믿을 뿐이고, 그 숫자를 정하는 것이 여기 조건이다. 특히 종료 조건의 상관 서브쿼리는 사람이
 * 읽어서는 맞는지 알기 어렵다.
 */
@SpringBootTest
@Sql("/sql/coupon-issue-fixture.sql")
class CouponEventQueryIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 꺼진_이벤트만_켜진다() {
        꺼둔다();

        assertThat(couponRepository.activateIfInactive(COUPON_ID, LocalDateTime.now())).isEqualTo(1);
        // 이미 켜졌으므로 두 번째 호출은 아무 행도 안 바꾼다
        assertThat(couponRepository.activateIfInactive(COUPON_ID, LocalDateTime.now())).isZero();
    }

    @Test
    void 마감_전이면_꺼지지_않는다() {
        마감을(LocalDateTime.now().plusDays(1));
        켜둔다();

        assertThat(끈다()).isZero();
    }

    /*
     * 마감은 지났지만 대기가 안 끝났다.
     * 이 창에서 끄면 아직 들어오는 플러시 때문에 발급 수를 잘못된 값으로 맞춘다.
     */
    @Test
    void 마감_직후_대기_안에서는_꺼지지_않는다() {
        마감을(LocalDateTime.now().minusSeconds(30));
        켜둔다();

        assertThat(끈다()).isZero();
    }

    /*
     * 소진으로는 안 꺼진다.
     * free 에 반납된 번호가 남아 있으면 스크립트가 다시 내주므로 소진이 최종이 아니고,
     * 스위치가 켜져 있어야 요청이 올 때 도는 회수가 묶인 번호를 되살린다.
     */
    @Test
    void 총량만큼_발급됐어도_마감_전이면_안_꺼진다() {
        마감을(LocalDateTime.now().plusDays(1));
        총량을(3);
        켜둔다();
        발급분을_채운다(3);

        assertThat(끈다()).isZero();
    }

    @Test
    void 마감에서_대기가_지나면_꺼진다() {
        마감을(LocalDateTime.now().minusMinutes(2));
        켜둔다();

        assertThat(끈다()).isEqualTo(1);
        assertThat(스위치가_켜져_있나()).isFalse();
    }

    // 이미 꺼진 것을 또 끄면 아무 행도 안 바뀐다. 배치가 여러 번 돌아도 같다
    @Test
    void 이미_꺼진_이벤트는_다시_안_꺼진다() {
        마감을(LocalDateTime.now().minusMinutes(2));
        꺼둔다();

        assertThat(끈다()).isZero();
    }

    @Test
    void 시작_전이면_발급_시각을_바꿀_수_있다() {
        시작을(LocalDateTime.now().plusDays(1));

        int changed = couponRepository.updateIssuePeriodIfNotStarted(
                COUPON_ID, LocalDateTime.now().plusDays(3), LocalDateTime.now().plusDays(4), LocalDateTime.now());

        assertThat(changed).isEqualTo(1);
    }

    @Test
    void 이미_시작했으면_발급_시각을_못_바꾼다() {
        시작을(LocalDateTime.now().minusMinutes(1));

        int changed = couponRepository.updateIssuePeriodIfNotStarted(
                COUPON_ID, LocalDateTime.now().plusDays(3), LocalDateTime.now().plusDays(4), LocalDateTime.now());

        assertThat(changed).isZero();
    }

    /*
     * 대기가 "진행 중인 플러시가 결판날 때까지 기다린다" 를 만든다.
     * 방금 마감한 쿠폰은 이 조건에 안 걸려야 한다.
     */
    @Test
    void 마감_직후_이벤트는_아직_후보가_아니다() {
        마감을(LocalDateTime.now().minusSeconds(30));
        켜둔다();

        assertThat(종료_후보()).doesNotContain(COUPON_ID);
    }

    @Test
    void 마감에서_대기가_지난_이벤트가_후보다() {
        마감을(LocalDateTime.now().minusMinutes(5));
        켜둔다();

        assertThat(종료_후보()).contains(COUPON_ID);
    }

    /*
     * 꺼진 것은 후보가 아니다.
     * 그래서 오래된 이벤트를 걸러 낼 상한이 필요 없다. 한 번 꺼지면 이 집합에서 영영 빠진다.
     */
    @Test
    void 이미_꺼진_이벤트는_후보가_아니다() {
        마감을(LocalDateTime.now().minusDays(8));
        꺼둔다();

        assertThat(종료_후보()).doesNotContain(COUPON_ID);
    }

    @Test
    void 발급_수를_실제_행_수로_맞춘다() {
        발급분을_채운다(3);

        couponRepository.syncIssuedQuantity(COUPON_ID);

        assertThat(발급_수()).isEqualTo(3);
    }

    // 대기 60초를 앱이 계산해 넘긴다
    private java.util.List<Long> 종료_후보() {
        return couponRepository.findClosableEvents(LocalDateTime.now().minusSeconds(60));
    }

    private int 끈다() {
        LocalDateTime now = LocalDateTime.now();
        return couponRepository.deactivateIfClosable(COUPON_ID, now.minusSeconds(60), now);
    }

    private void 켜둔다() {
        jdbcTemplate.update("UPDATE coupon SET is_active = TRUE WHERE coupon_id = ?", COUPON_ID);
    }

    /*
     * 앱이 방금 끈 것을 흉내 낸다.
     * updated_at 을 앱 시각으로 함께 쓰는 것이 중요하다. 픽스처의 NOW(6) 는 DB 서버 시각이라
     * 개발자 기계에서 앱 시각과 시차만큼 어긋나고, 그러면 이 행이 정리 대상으로 잘못 걸린다.
     */
    private void 꺼둔다() {
        꺼둔지(LocalDateTime.now());
    }

    // updated_at 이 "언제 껐나" 를 뜻하고 정리 조건이 그것을 잰다
    private void 꺼둔지(LocalDateTime closedAt) {
        jdbcTemplate.update("UPDATE coupon SET is_active = FALSE, updated_at = ? WHERE coupon_id = ?",
                closedAt, COUPON_ID);
    }

    private void 마감을(LocalDateTime issueEndAt) {
        jdbcTemplate.update("UPDATE coupon SET issue_end_at = ? WHERE coupon_id = ?", issueEndAt, COUPON_ID);
    }

    private void 시작을(LocalDateTime issueStartAt) {
        jdbcTemplate.update("UPDATE coupon SET issue_start_at = ? WHERE coupon_id = ?", issueStartAt, COUPON_ID);
    }

    private void 총량을(int totalQuantity) {
        jdbcTemplate.update("UPDATE coupon SET total_quantity = ? WHERE coupon_id = ?", totalQuantity, COUPON_ID);
    }

    /*
     * issue_limit 은 그 시점의 total_quantity 를 그대로 넣는다.
     * chk_mc_issue_seq 가 순번을 그 값과 견주므로 둘이 어긋나면 행이 안 들어간다.
     */
    private void 발급분을_채운다(int count) {
        Integer issueLimit = jdbcTemplate.queryForObject(
                "SELECT total_quantity FROM coupon WHERE coupon_id = ?", Integer.class, COUPON_ID);
        for (int seq = 1; seq <= count; seq++) {
            jdbcTemplate.update("""
                    INSERT INTO member_coupon
                        (coupon_id, member_id, scope, issue_limit, issue_seq, status, issued_at, created_at, updated_at)
                    VALUES (?, ?, 'ORDER', ?, ?, 'ISSUED', NOW(6), NOW(6), NOW(6))
                    """, COUPON_ID, 9100 + seq, issueLimit, seq);
        }
    }

    private boolean 스위치가_켜져_있나() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM coupon WHERE coupon_id = ?", Boolean.class, COUPON_ID));
    }

    private Integer 발급_수() {
        return jdbcTemplate.queryForObject(
                "SELECT issued_quantity FROM coupon WHERE coupon_id = ?", Integer.class, COUPON_ID);
    }
}
