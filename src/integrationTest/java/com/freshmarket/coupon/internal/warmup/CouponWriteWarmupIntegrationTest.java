package com.freshmarket.coupon.internal.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.MemberCouponBulkRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/*
 * 쓰기 워밍업이 정말로 한 행도 안 남기는지 본다.
 *
 * 이 테스트의 값어치가 전부 여기에 있다. 데우는 것은 로그로 확인할 수 있지만 "안 남는다" 는
 * 눈으로 못 본다. 롤백이 빠지거나 커밋으로 바뀌면 가짜 회원과 가짜 발급분이 운영 데이터에
 * 쌓이는데, 그것을 알아차릴 다른 장치가 없다.
 *
 * 성능은 여기서 재지 않는다. 이 MySQL 은 매 회차 새 컨테이너라 개선의 일부가 JIT 이 아니라
 * 버퍼 풀이 데워진 몫이고, 그렇게 고른 값을 운영에 박으면 근거 없는 값이 근거 있어 보인다.
 * write-rows 는 AWS 회차에서 정한다.
 */
@SpringBootTest
class CouponWriteWarmupIntegrationTest extends IntegrationTestSupport {

    // V33 이 심는 워밍업 전용 쿠폰이다
    private static final long WARMUP_COUPON_ID = 1_000_000L;

    private static final int ROWS = 40;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemberCouponBulkRepository bulkRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 데우고_한_행도_남기지_않는다() {
        long 회원_전 = 회원_수();

        assertThat(warmup(ROWS).warmUp()).isEqualTo(ROWS);

        assertThat(회원_수()).as("워밍업 회원이 남으면 안 된다").isEqualTo(회원_전);
        assertThat(워밍업_회원_수()).as("provider 가 WARMUP 인 행이 남으면 안 된다").isZero();
        assertThat(발급분_수()).as("워밍업 발급분이 남으면 안 된다").isZero();
    }

    /*
     * 라운드를 여러 번 도는 경로다.
     * 라운드마다 같은 식별자를 다시 쓰므로, 앞 라운드가 제대로 안 되돌아가면 둘째 라운드가
     * uk_mc_coupon_member 에 걸려 행 수가 모자란다.
     */
    @Test
    void 라운드를_나눠_돌아도_행_수가_맞는다() {
        assertThat(warmup(ROWS, ROWS / 4).warmUp()).isEqualTo(ROWS);

        assertThat(워밍업_회원_수()).isZero();
        assertThat(발급분_수()).isZero();
    }

    // 0 이면 DB 를 아예 안 건드린다
    @Test
    void 행_수가_0_이면_아무것도_하지_않는다() {
        assertThat(warmup(0).warmUp()).isZero();
    }

    private CouponWriteWarmup warmup(int rows) {
        return warmup(rows, rows == 0 ? 1 : rows);
    }

    private CouponWriteWarmup warmup(int rows, int chunk) {
        CouponWarmupProperties properties = new CouponWarmupProperties(
                true, WARMUP_COUPON_ID, 1, 1, Duration.ofSeconds(60), rows, Duration.ofSeconds(20));
        CouponIssueProperties issueProperties = new CouponIssueProperties(
                Duration.ofSeconds(60), Duration.ofMillis(20), chunk, 1,
                Integer.MAX_VALUE, Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5));
        return new CouponWriteWarmup(jdbcTemplate, bulkRepository, properties, issueProperties, transactionManager);
    }

    private long 회원_수() {
        return count("SELECT COUNT(*) FROM member");
    }

    private long 워밍업_회원_수() {
        return count("SELECT COUNT(*) FROM member WHERE provider = 'WARMUP'");
    }

    private long 발급분_수() {
        return count("SELECT COUNT(*) FROM member_coupon WHERE coupon_id = " + WARMUP_COUPON_ID);
    }

    private long count(String sql) {
        Long found = jdbcTemplate.queryForObject(sql, Long.class);
        return found == null ? 0 : found;
    }
}
