package com.freshmarket.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.coupon.domain.audit.CouponConsistencyReport;
import com.freshmarket.coupon.domain.audit.CouponIssueCount;
import com.freshmarket.coupon.domain.audit.CouponSeqSpan;
import com.freshmarket.coupon.domain.service.CouponConsistencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/*
 * 검증 쿼리가 실제로 어긋남을 잡아내는지 본다.
 *
 * 어긋난 데이터를 일부러 만들어 넣는 것이 이 시험의 전부다. 정상 데이터에서 "깨끗하다" 가
 * 나오는 것은 아무것도 증명하지 않는다. 아무것도 안 보는 검증도 같은 답을 낸다.
 *
 * 다른 시험이 남긴 쿠폰까지 리포트에 들어오므로 목록 길이를 재지 않고 이 시험의 쿠폰만 골라 본다.
 */
@SpringBootTest
@Sql("/sql/coupon-issue-fixture.sql")
class CouponConsistencyIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;

    @Autowired
    private CouponConsistencyService sut;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 맞는_쿠폰은_리포트에_안_들어온다() {
        발급한다(1, 2, 3);
        카운터를_적는다(3);

        CouponConsistencyReport report = sut.verify();

        assertThat(재고_어긋남()).isEmpty();
        assertThat(순번_구멍()).isEmpty();
        assertThat(report.duplicates()).noneMatch(duplicate -> duplicate.couponId() == COUPON_ID);
    }

    // 카운터가 실제 행 수와 다르면 그 값을 그대로 보여 줘야 어디가 틀어졌는지 읽힌다
    @Test
    void 카운터가_실제_행_수와_다른_것을_잡는다() {
        발급한다(1, 2, 3);
        카운터를_적는다(99);

        sut.verify();

        assertThat(재고_어긋남()).singleElement()
                .satisfies(counted -> {
                    assertThat(counted.issuedQuantity()).isEqualTo(99);
                    assertThat(counted.actual()).isEqualTo(3);
                });
    }

    /*
     * 번호는 나갔는데 행이 안 들어간 모양을 만든다.
     * 3번을 빼면 MAX 가 4 이고 행이 3 개라 구멍이 하나다.
     */
    @Test
    void 순번의_구멍을_잡는다() {
        발급한다(1, 2, 4);
        카운터를_적는다(3);

        sut.verify();

        assertThat(순번_구멍()).singleElement()
                .satisfies(span -> {
                    assertThat(span.maxSeq()).isEqualTo(4);
                    assertThat(span.issued()).isEqualTo(3);
                    assertThat(span.gap()).isEqualTo(1);
                });
    }

    /*
     * chk_coupon_quantity 가 issued_quantity <= total_quantity 를 막고 있어 카운터는 못 넘긴다.
     * 하지만 실제 발급 행 수가 넘는 것은 DB 가 못 막는다. 이 항목이 있는 이유가 그것이다.
     */
    @Test
    void 한정_수량을_넘겨_발급된_것을_잡는다() {
        발급한다(1, 2, 3);
        jdbcTemplate.update(
                "UPDATE coupon SET total_quantity = 2, issued_quantity = 2 WHERE coupon_id = ?", COUPON_ID);

        sut.verify();

        assertThat(재고_어긋남()).singleElement()
                .satisfies(counted -> {
                    assertThat(counted.exceedsTotal()).isTrue();
                    assertThat(counted.actual()).isEqualTo(3);
                });
    }

    // 다 안 나간 것은 어긋남이 아니다. 진행 중인 이벤트가 매번 걸리면 리포트를 아무도 안 본다
    @Test
    void 다_안_나간_이벤트는_안_잡는다() {
        발급한다(1, 2, 3);
        카운터를_적는다(3);

        sut.verify();

        assertThat(재고_어긋남()).isEmpty();
    }

    @Test
    void 마지막_전이가_상태와_다른_것을_잡는다() {
        발급한다(1);
        카운터를_적는다(1);
        long memberCouponId = 발급분_번호(1);
        // 상태는 ISSUED 인데 이력의 마지막 전이는 USED 다. R5 의 규율이 깨진 모양이다
        jdbcTemplate.update("""
                INSERT INTO member_coupon_status_history
                       (member_coupon_id, from_status, to_status, reason, created_at)
                VALUES (?, 'ISSUED', 'USED', '어긋남 주입', NOW(6))
                """, memberCouponId);

        assertThat(sut.verify().statusHistoryMismatches()).isPositive();
    }

    /*
     * 1인 1매는 결과로 확인하는 항목이다.
     * uk_mc_coupon_member 가 막고 있어 검증이 잡을 행을 만들 수 없다. 막고 있다는 것을 여기서
     * 보이고, 검증은 그 제약이 지워지거나 우회 경로가 생겼을 때를 위해 남는다.
     */
    @Test
    void 한_회원이_두_장_받는_것은_DB_가_막는다() {
        발급한다(1);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO member_coupon
                    (coupon_id, member_id, scope, issue_limit, issue_seq, status, issued_at, created_at, updated_at)
                VALUES (?, 9101, 'ORDER', 100, 2, 'ISSUED', NOW(6), NOW(6), NOW(6))
                """, COUPON_ID)).isInstanceOf(DuplicateKeyException.class);

        assertThat(sut.verify().duplicates()).noneMatch(duplicate -> duplicate.couponId() == COUPON_ID);
    }

    /*
     * 요구사항이 "같은 데이터 기준으로 재실행하면 같은 결과" 를 요구한다.
     * 검증이 무엇이든 고치면 두 번째 회차가 첫 번째와 다른 답을 낸다.
     */
    @Test
    void 두_번_돌려도_같은_결과가_나오고_데이터가_안_바뀐다() {
        발급한다(1, 2, 4);
        카운터를_적는다(99);

        CouponConsistencyReport 첫_회차 = sut.verify();
        CouponConsistencyReport 두_번째_회차 = sut.verify();

        assertThat(두_번째_회차).isEqualTo(첫_회차);
        assertThat(카운터()).isEqualTo(99);
        assertThat(발급_행_수()).isEqualTo(3);
    }

    private void 발급한다(int... seqs) {
        for (int seq : seqs) {
            jdbcTemplate.update("""
                    INSERT INTO member_coupon
                        (coupon_id, member_id, scope, issue_limit, issue_seq, status, issued_at, created_at, updated_at)
                    VALUES (?, ?, 'ORDER', 100, ?, 'ISSUED', NOW(6), NOW(6), NOW(6))
                    """, COUPON_ID, 9100L + seq, seq);
        }
    }

    // 발급 중에는 아무도 이 값을 안 올린다. 정리 배치가 나중에 맞추는 값이라 시험이 직접 적는다
    private void 카운터를_적는다(int issued) {
        jdbcTemplate.update("UPDATE coupon SET issued_quantity = ? WHERE coupon_id = ?", issued, COUPON_ID);
    }

    private long 발급분_번호(int seq) {
        return jdbcTemplate.queryForObject(
                "SELECT member_coupon_id FROM member_coupon WHERE coupon_id = ? AND issue_seq = ?",
                Long.class, COUPON_ID, seq);
    }

    private List<CouponIssueCount> 재고_어긋남() {
        return sut.verify().stock().stream().filter(counted -> counted.couponId() == COUPON_ID).toList();
    }

    private List<CouponSeqSpan> 순번_구멍() {
        return sut.verify().seqGaps().stream().filter(span -> span.couponId() == COUPON_ID).toList();
    }

    private int 카운터() {
        return jdbcTemplate.queryForObject(
                "SELECT issued_quantity FROM coupon WHERE coupon_id = ?", Integer.class, COUPON_ID);
    }

    private int 발급_행_수() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_coupon WHERE coupon_id = ?", Integer.class, COUPON_ID);
    }
}
