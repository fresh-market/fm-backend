package com.freshmarket.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.IntegrationTestSupport;
import java.util.List;

import com.freshmarket.coupon.domain.audit.CouponConsistencyReport;
import com.freshmarket.coupon.domain.service.CouponConsistencyService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/*
 * 정합성 검증용 더미데이터 스크립트를 실제 MySQL 에 태워 본다.
 *
 * 이 데이터가 10장 검증의 대상이므로, 데이터 자체가 제약을 어기면 검증이 무엇을 재는지 알 수 없다.
 * chk_mc_issue_seq, chk_mc_used_at, chk_mcsh_transition, uk_mc_coupon_seq 가 한꺼번에 걸리는 자리다.
 *
 * 스크립트를 클래스당 한 번만 돌린다. 400만 행을 넣는 준비를 시험마다 다시 할 이유가 없다.
 */
/*
 * 전역 socketTimeout 10초를 이 시험에서만 푼다.
 * 검증 배치가 300만 행을 통째로 훑도록 만든 것이라 그 한 문장이 10초를 넘긴다.
 * 운영에서는 application-batch.yml 이 같은 이유로 300초를 준다.
 */
@SpringBootTest(properties = "spring.datasource.hikari.data-source-properties.socketTimeout=300000")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeedDummyDataScriptIntegrationTest extends IntegrationTestSupport {

    private static final String SCRIPT = "loadtest/seed-dummy-data.sql";
    private static final int MEMBERS = 1_000_000;
    private static final int ISSUES = 3_000_000;

    private JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private CouponConsistencyService couponConsistencyService;

    /*
     * 앱의 DataSource 를 안 쓴다.
     * 전역 socketTimeout 이 10초인데 400만 행 적재도, 그것을 훑는 검증 쿼리도 그보다 오래 걸린다.
     * 이 스크립트는 원래 mysql CLI 로 도는 것이라 그쪽과 같은 조건에서 검증한다.
     */
    @BeforeAll
    void 더미데이터를_적재한다() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl() + "&socketTimeout=0", MYSQL.getUsername(), MYSQL.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);

        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(SCRIPT));
        }
    }

    @Test
    void 요구사항이_말한_규모를_적재한다() {
        assertThat(count("SELECT COUNT(*) FROM member WHERE provider_user_id LIKE 'dummy-%'"))
                .isEqualTo(MEMBERS);
        assertThat(count("""
                SELECT COUNT(*) FROM member_coupon mc JOIN coupon c ON c.coupon_id = mc.coupon_id
                 WHERE c.name LIKE '더미%'
                """)).isEqualTo(ISSUES);
    }

    /*
     * 상태만 흩어 두고 이력을 안 넣으면 10장의 "상태와 이력의 마지막 전이가 같은가" 가
     * 300만 건 전부에서 어긋난다. 더미데이터가 검증을 통과하지 못하는 데이터가 된다.
     */
    @Test
    void 모든_발급분의_마지막_전이가_현재_상태와_같다() {
        /*
         * 상관 서브쿼리로 쓰면 300만 번 도는 형태가 되어 끝나지 않는다.
         * 마지막 전이를 한 번에 모아 조인한다. 정합성 검증 배치도 같은 모양이어야 한다.
         */
        Integer 어긋난_건수 = count("""
                SELECT COUNT(*)
                  FROM member_coupon mc
                  JOIN coupon c ON c.coupon_id = mc.coupon_id
                  JOIN (SELECT member_coupon_id, MAX(member_coupon_status_history_id) AS last_id
                          FROM member_coupon_status_history
                         GROUP BY member_coupon_id) last_of
                    ON last_of.member_coupon_id = mc.member_coupon_id
                  JOIN member_coupon_status_history h
                    ON h.member_coupon_status_history_id = last_of.last_id
                 WHERE c.name LIKE '더미%' AND mc.status <> h.to_status
                """);

        assertThat(어긋난_건수).isZero();
    }

    // 한정 쿠폰은 순번이 1..total_quantity 로 빠짐없이 들어가야 순번 연속성 검증의 대상이 된다
    @Test
    void 한정_쿠폰의_순번이_연속이다() {
        Integer 구멍 = count("""
                SELECT COALESCE(SUM(gap), 0) FROM (
                    SELECT MAX(mc.issue_seq) - COUNT(*) AS gap
                      FROM member_coupon mc
                      JOIN coupon c ON c.coupon_id = mc.coupon_id
                     WHERE c.name LIKE '더미 한정%'
                     GROUP BY mc.coupon_id) g
                """);

        assertThat(구멍).isZero();
    }

    // 무제한 쿠폰은 issue_limit 과 issue_seq 가 둘 다 NULL 이어야 chk_mc_issue_seq 를 지난다
    @Test
    void 무제한_쿠폰은_순번을_갖지_않는다() {
        Integer 순번이_있는_것 = count("""
                SELECT COUNT(*) FROM member_coupon mc
                  JOIN coupon c ON c.coupon_id = mc.coupon_id
                 WHERE c.name = '더미 무제한' AND (mc.issue_seq IS NOT NULL OR mc.issue_limit IS NOT NULL)
                """);

        assertThat(순번이_있는_것).isZero();
    }

    // 네 상태가 다 들어 있어야 "전체 상태를 관리한다" 를 검증할 대상이 된다
    @Test
    void 네_상태가_모두_들어_있다() {
        Integer 상태_종류 = count("""
                SELECT COUNT(DISTINCT mc.status) FROM member_coupon mc
                  JOIN coupon c ON c.coupon_id = mc.coupon_id
                 WHERE c.name LIKE '더미%'
                """);

        assertThat(상태_종류).isEqualTo(4);
    }

    /*
     * 검증 배치를 300만 건 전체에 실제로 태운다.
     * 요구사항이 "300만 건 전체를 대상으로" 를 요구하므로, 표본으로 도는 것이 아니라 전부 훑고
     * 끝난다는 것이 확인되어야 한다. 상관 서브쿼리로 짜면 여기서 안 끝난다.
     *
     * 다른 시험이 남긴 쿠폰까지 리포트에 들어오므로 더미 쿠폰만 골라 본다.
     */
    @Test
    void 검증_배치가_삼백만_건을_훑고_어긋남을_못_찾는다() {
        List<Long> 더미_쿠폰 = jdbcTemplate.queryForList(
                "SELECT coupon_id FROM coupon WHERE name LIKE '더미%'", Long.class);

        CouponConsistencyReport report = couponConsistencyService.verify();

        assertThat(report.stock()).noneMatch(counted -> 더미_쿠폰.contains(counted.couponId()));
        assertThat(report.seqGaps()).noneMatch(span -> 더미_쿠폰.contains(span.couponId()));
        assertThat(report.duplicates()).noneMatch(duplicate -> 더미_쿠폰.contains(duplicate.couponId()));
        assertThat(report.statusHistoryMismatches()).isZero();
    }

    private Integer count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}
