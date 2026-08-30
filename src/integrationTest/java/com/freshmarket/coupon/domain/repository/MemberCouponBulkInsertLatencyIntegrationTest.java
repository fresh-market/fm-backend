package com.freshmarket.coupon.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.coupon.domain.entity.CouponScope;
import com.freshmarket.coupon.domain.issue.IssueTicket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 빈 표에 한 배치를 넣는 데 걸리는 시간을 잰다.
 *
 * 워밍업을 먼저 도는 이유는 재려는 값이 정상 상태의 플러시 한 번이기 때문이다. 첫 호출에는
 * JIT 컴파일과 클래스 로딩, 커넥션 생성, 비어 있는 버퍼 풀이 함께 실려서 그 값은 배치 비용이
 * 아니라 이 프로세스의 첫 DB 쓰기 비용이 된다.
 *
 * 회차마다 발급 행을 지우고 시작하므로 표는 언제나 비어 있다. 인덱스가 자라며 생기는 페이지
 * 분할이 뒤 회차에 실리지 않게 하려는 것이다.
 */
@SpringBootTest
class MemberCouponBulkInsertLatencyIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9501L;
    private static final long FIRST_MEMBER_ID = 950001L;
    private static final int MEMBER_COUNT = 1000;
    private static final int ISSUE_LIMIT = MEMBER_COUNT;

    private static final int WARMUP_ROUNDS = 20;
    private static final int MEASURED_ROUNDS = 30;

    private static final Path REPORT = Path.of("build", "tmp", "member-coupon-insert-latency.txt");

    @Autowired
    private MemberCouponBulkRepository sut;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 회원과_쿠폰을_준비한다() {
        발급행을_비운다();
        jdbcTemplate.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?",
                FIRST_MEMBER_ID, FIRST_MEMBER_ID + MEMBER_COUNT - 1);
        jdbcTemplate.update("DELETE FROM coupon WHERE coupon_id = ?", COUPON_ID);

        jdbcTemplate.update("""
                INSERT INTO coupon (coupon_id, name, scope, discount_type, discount_value,
                                    min_order_amount, total_quantity, issued_quantity,
                                    issue_start_at, issue_end_at, valid_from, valid_to, is_active,
                                    created_at, updated_at)
                VALUES (?, '벤치마크 선착순 쿠폰', 'ORDER', 'AMOUNT', 1000,
                        0, ?, 0,
                        '2026-01-01 00:00:00', '2030-01-01 00:00:00', '2026-01-01', '2030-01-01', TRUE,
                        NOW(6), NOW(6))
                """, COUPON_ID, ISSUE_LIMIT);

        jdbcTemplate.batchUpdate("""
                INSERT INTO member (member_id, provider_user_id, member_grade_id, status, created_at, updated_at)
                VALUES (?, ?, 1, 'ACTIVE', NOW(6), NOW(6))
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setLong(1, FIRST_MEMBER_ID + i);
                ps.setString(2, "it-bench-" + (FIRST_MEMBER_ID + i));
            }

            @Override
            public int getBatchSize() {
                return MEMBER_COUNT;
            }
        });
    }

    @Test
    void 빈_표에_배칭_insert_한_번이_걸리는_시간을_잰다() throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("mysql=8.4  rewriteBatchedStatements=true  autocommit  빈 표 기준\n")
                .append("warmup=").append(WARMUP_ROUNDS)
                .append("  measured=").append(MEASURED_ROUNDS).append("\n\n");

        워밍업한다();
        report.append(측정한다(500));
        report.append(측정한다(1000));

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report.toString());
        System.out.println(report);
    }

    private void 워밍업한다() {
        List<IssueTicket> batch = 티켓(500);
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            발급행을_비운다();
            sut.insertAll(batch);
        }
    }

    private String 측정한다(int rows) {
        List<IssueTicket> batch = 티켓(rows);
        long[] elapsedMicros = new long[MEASURED_ROUNDS];

        for (int i = 0; i < MEASURED_ROUNDS; i++) {
            발급행을_비운다();

            long start = System.nanoTime();
            sut.insertAll(batch);
            elapsedMicros[i] = (System.nanoTime() - start) / 1_000;

            assertThat(발급된_행_수()).isEqualTo(rows);
        }

        Arrays.sort(elapsedMicros);
        return """
                %d 행
                  min  %.2f ms
                  p50  %.2f ms
                  p90  %.2f ms
                  p95  %.2f ms
                  max  %.2f ms
                  mean %.2f ms
                  행당 %.1f us (p50 기준)

                """.formatted(rows,
                ms(elapsedMicros[0]),
                ms(백분위(elapsedMicros, 50)),
                ms(백분위(elapsedMicros, 90)),
                ms(백분위(elapsedMicros, 95)),
                ms(elapsedMicros[MEASURED_ROUNDS - 1]),
                ms(평균(elapsedMicros)),
                (double) 백분위(elapsedMicros, 50) / rows);
    }

    private static double ms(double micros) {
        return micros / 1_000;
    }

    private static long 백분위(long[] sorted, int percentile) {
        int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
        return sorted[Math.max(0, index)];
    }

    private static double 평균(long[] values) {
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return (double) sum / values.length;
    }

    private List<IssueTicket> 티켓(int rows) {
        List<IssueTicket> batch = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            batch.add(IssueTicket.of(COUPON_ID, FIRST_MEMBER_ID + i, CouponScope.ORDER, ISSUE_LIMIT, i + 1));
        }
        return batch;
    }

    private void 발급행을_비운다() {
        jdbcTemplate.update("DELETE FROM member_coupon WHERE coupon_id = ?", COUPON_ID);
    }

    private Integer 발급된_행_수() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_coupon WHERE coupon_id = ?", Integer.class, COUPON_ID);
    }
}
