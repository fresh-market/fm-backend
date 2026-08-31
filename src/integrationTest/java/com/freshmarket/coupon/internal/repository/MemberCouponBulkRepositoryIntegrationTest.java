package com.freshmarket.coupon.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.List;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.issue.IssueTicket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/*
 * 발급 행의 시각을 누가 찍는지 본다.
 *
 * 앱이 아니라 DB 가 찍어야 하는 이유는 발급 인스턴스가 여럿이기 때문이다. 인스턴스마다 시계가
 * 어긋나면 issue_seq 가 매긴 순서와 created_at 이 말하는 순서가 갈라지고, 선착순을 나중에
 * 증명하려 할 때 근거 둘이 서로 다른 이야기를 한다.
 */
@SpringBootTest
@Sql("/sql/coupon-issue-fixture.sql")
class MemberCouponBulkRepositoryIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;
    private static final int ISSUE_LIMIT = 100;

    @Autowired
    private MemberCouponBulkRepository sut;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /*
     * rewriteBatchedStatements 가 배치를 다중행 INSERT 문장 하나로 합치고, MySQL 은 NOW(6) 을
     * 한 문장에서 한 번만 평가한다. 그래서 한 번의 플러시로 나간 행들이 같은 시각을 갖는다.
     * 앱이 행마다 시각을 만들면 한 배치 안에서도 값이 흩어진다.
     */
    @Test
    void 한_배치로_넣은_행들이_같은_시각을_갖는다() {
        sut.insertAll(List.of(요청(9101L, 1), 요청(9102L, 2), 요청(9103L, 3)));

        assertThat(발급된_행_수()).isEqualTo(3);
        assertThat(서로_다른_생성시각_수()).isEqualTo(1);
    }

    // 한 행 안의 세 시각은 같은 문장이 찍으므로 서로 어긋날 수 없다.
    @Test
    void 한_행의_발급_생성_수정_시각이_모두_같다() {
        sut.insertOne(요청(9101L, 1));

        assertThat(세_시각이_같은_행_수()).isEqualTo(1);
    }

    /*
     * 앱 시계가 아니라 DB 시계에서 왔는지 본다.
     * 넣기 직전과 직후에 DB 에게 물어본 시각 사이에 행의 시각이 들어 있어야 한다.
     */
    @Test
    void 시각을_DB_가_찍는다() {
        Timestamp before = DB_의_지금();
        sut.insertOne(요청(9101L, 1));
        Timestamp after = DB_의_지금();

        Timestamp issued = 발급_시각(9101L);
        assertThat(issued).isBetween(before, after);
    }

    private static IssueTicket 요청(long memberId, int issueSeq) {
        return IssueTicket.of(COUPON_ID, memberId, CouponScope.ORDER, ISSUE_LIMIT, issueSeq);
    }

    private Integer 발급된_행_수() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_coupon WHERE coupon_id = ?", Integer.class, COUPON_ID);
    }

    private Integer 서로_다른_생성시각_수() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT created_at) FROM member_coupon WHERE coupon_id = ?",
                Integer.class, COUPON_ID);
    }

    private Integer 세_시각이_같은_행_수() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM member_coupon
                 WHERE coupon_id = ? AND issued_at = created_at AND created_at = updated_at
                """, Integer.class, COUPON_ID);
    }

    private Timestamp DB_의_지금() {
        return jdbcTemplate.queryForObject("SELECT NOW(6)", Timestamp.class);
    }

    private Timestamp 발급_시각(long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT issued_at FROM member_coupon WHERE coupon_id = ? AND member_id = ?",
                Timestamp.class, COUPON_ID, memberId);
    }
}
