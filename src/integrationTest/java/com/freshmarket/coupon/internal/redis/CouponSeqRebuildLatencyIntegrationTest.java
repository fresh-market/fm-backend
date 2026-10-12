package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.freshmarket.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 복구가 실제 규모에서 얼마나 걸리는지 잰다.
 *
 * 카운터가 서기 전까지 그 쿠폰의 요청이 혼잡으로 끊기므로, 복구가 오래 걸리면 Redis 가 돌아온
 * 뒤에도 그만큼 돌려보낸다.
 *
 * 기다림을 줄이는 설정이 없다. 복구가 더 이상 아무도 기다리지 않으므로 여기서 재는 값이 곧
 * 전부다 (docs/coupon/rebuild-rearchitecture-2026-10-12.md 5.2).
 */
@SpringBootTest
class CouponSeqRebuildLatencyIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9601L;
    private static final long FIRST_MEMBER_ID = 960001L;

    // 요구 조건의 재고와 같다. 발급 행 수의 상한이 곧 이 경로가 읽을 행 수의 상한이다
    private static final int TOTAL_QUANTITY = 10_000;

    /*
     * 열 명 중 하나는 번호만 받고 사라진 것으로 둔다.
     * 결번이 있는 쪽이 실제 회차에 가깝다. 로컬 회차 13 에서 1만 중 1,554 였다.
     *
     * 옛 설계에서는 이 수가 곧 free 에 쓸 항목 수였다. 지금은 free 를 안 세우므로 쓰기 비용에
     * 안 들어가고, 발급 행을 읽는 양만 남는다.
     */
    private static final int GAP_EVERY = 10;

    private static final Path REPORT = Path.of("build", "tmp", "coupon-seq-rebuild-latency.txt");

    @Autowired
    private CouponSeqRebuilder sut;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 만_건짜리_이벤트를_세운다() {
        비운다();
        쿠폰을_만든다();
        회원을_만든다();
        발급행을_채운다();
        키를_지운다();
    }

    @Test
    void 만_건_규모의_재건_시간을_잰다() throws IOException {
        int 발급행 = 발급행_수();

        long start = System.nanoTime();
        sut.rebuild(COUPON_ID);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 잰 값이 뜻을 가지려면 복구가 실제로 끝나 있어야 한다
        assertThat(redisTemplate.opsForValue().get(counter())).isNotNull();
        assertThat(redisTemplate.opsForHash().size(seq())).isEqualTo(발급행);

        String report = """
                복구 지연 (기다림이 없다)

                발급행       %,d
                걸린 시간    %,d ms
                """.formatted(발급행, elapsedMillis);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        System.out.println(report);
    }

    /*
     * 다른 인스턴스가 자기 큐를 올린 것을 흉내 낸다.
     * 이 JVM 의 큐에 넣으면 플러시 스레드가 곧바로 가져가 회차마다 결과가 달라진다.
     */
    private void 발급행을_채운다() {
        List<int[]> rows = new ArrayList<>(TOTAL_QUANTITY);
        for (int seq = 1; seq <= TOTAL_QUANTITY; seq++) {
            if (seq % GAP_EVERY == 0) {
                // 이 번호는 나갔지만 행이 없다. 재건이 free 로 되살릴 대상이다
                continue;
            }
            rows.add(new int[]{seq, seq});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO member_coupon
                    (coupon_id, member_id, scope, issue_limit, issue_seq, status, issued_at, created_at, updated_at)
                VALUES (?, ?, 'ORDER', ?, ?, 'ISSUED', NOW(6), NOW(6), NOW(6))
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setLong(1, COUPON_ID);
                ps.setLong(2, FIRST_MEMBER_ID + rows.get(i)[0]);
                ps.setInt(3, TOTAL_QUANTITY);
                ps.setInt(4, rows.get(i)[1]);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    private void 회원을_만든다() {
        jdbcTemplate.batchUpdate("""
                INSERT INTO member (member_id, provider_user_id, member_grade_id, status, created_at, updated_at)
                VALUES (?, ?, 1, 'ACTIVE', NOW(6), NOW(6))
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setLong(1, FIRST_MEMBER_ID + i);
                ps.setString(2, "it-rebuild-" + (FIRST_MEMBER_ID + i));
            }

            @Override
            public int getBatchSize() {
                return TOTAL_QUANTITY + 1;
            }
        });
    }

    private void 쿠폰을_만든다() {
        jdbcTemplate.update("""
                INSERT INTO coupon (coupon_id, name, scope, discount_type, discount_value,
                                    min_order_amount, total_quantity, issued_quantity,
                                    issue_start_at, issue_end_at, valid_from, valid_to, is_active,
                                    created_at, updated_at)
                VALUES (?, '재건 지연 측정 쿠폰', 'ORDER', 'AMOUNT', 1000,
                        0, ?, 0,
                        '2026-01-01 00:00:00', '2030-01-01 00:00:00', '2026-01-01', '2030-01-01', TRUE,
                        NOW(6), NOW(6))
                """, COUPON_ID, TOTAL_QUANTITY);
    }

    private void 비운다() {
        jdbcTemplate.update("""
                DELETE FROM member_coupon_status_history
                 WHERE member_coupon_id IN (SELECT member_coupon_id FROM member_coupon WHERE coupon_id = ?)
                """, COUPON_ID);
        jdbcTemplate.update("DELETE FROM member_coupon WHERE coupon_id = ?", COUPON_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?",
                FIRST_MEMBER_ID, FIRST_MEMBER_ID + TOTAL_QUANTITY);
        jdbcTemplate.update("DELETE FROM coupon WHERE coupon_id = ?", COUPON_ID);
    }

    private void 키를_지운다() {
        redisTemplate.delete(List.of(seq(), free(), counter(),
                "coupon:" + COUPON_ID + ":pending"));
    }

    private int 발급행_수() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_coupon WHERE coupon_id = ?", Integer.class, COUPON_ID);
        return count == null ? 0 : count;
    }

    private String seq() {
        return "coupon:" + COUPON_ID + ":seq";
    }

    private String free() {
        return "coupon:" + COUPON_ID + ":free";
    }

    private String counter() {
        return "coupon:" + COUPON_ID + ":counter";
    }
}
