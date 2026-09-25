package com.freshmarket.coupon.internal.warmup;

import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.issue.IssueTicket;
import com.freshmarket.coupon.internal.repository.MemberCouponBulkRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배치 INSERT 경로를 한 행도 남기지 않고 데운다.
 *
 * <p><b>넣고 되돌린다.</b> 워밍업용 회원과 그 회원의 발급분을 한 트랜잭션 안에서 넣고 통째로
 * 롤백한다. 외래 키 검사는 같은 트랜잭션 안을 보므로 통과하고, 되돌리면 회원 행까지 사라진다.
 *
 * <p>왜 필요한가. {@link CouponWarmupRunner} 가 보내는 HTTP 요청은 소진으로 끝나 큐 앞에서
 * 멈춘다. 그래서 읽기 경로만 데워지고 배치 INSERT 는 차가운 채로 남는다.
 *
 * <pre>
 * 러너의 HTTP   톰캣, 시큐리티 필터, JWT 검증, 캐시 조회, 회로, 응답 직렬화
 * 이 클래스     Hikari, Connector/J, rewriteBatchedStatements, 행 바인딩, MySQL 파싱, FK 검사
 * </pre>
 *
 * <p><b>둘을 합쳐도 큐 뒤는 못 데운다.</b> 큐 submit 과 플러시 스레드의 배치 수집과
 * {@code markCommitted} 와 future 완료는 커밋이 성공해야만 도는 코드다. 롤백하면 그 앞에서
 * 끝나고, 돌게 하려면 커밋해야 하고, 커밋하면 행이 남는다. 이 방식으로는 못 넘는 벽이다.
 *
 * <p><b>운영과 모드가 다르다는 것도 알고 쓴다.</b> 실제 플러시는 트랜잭션을 안 연다.
 * {@code CouponIssueFlusher} 에 {@code @Transactional} 이 없고 {@code JdbcTemplate} 이
 * autocommit 커넥션으로 쓴다. 이 클래스는 트랜잭션을 열므로 커넥션 획득과 autocommit 토글에서
 * 다른 갈래를 탄다. 행당 비용의 대부분은 같은 코드라 데우려던 것은 데워지지만 똑같지는 않다.
 *
 * <p>자세한 배경은 {@code docs/coupon/warmup.md} 에 있다.
 */
@Slf4j
@Component
@Profile("coupon")
public class CouponWriteWarmup {

    /*
     * 워밍업 회원의 인증 제공자다. 실재하는 제공자와 겹치면 안 된다.
     *
     * member 의 active_provider_key 가 provider 와 provider_user_id 를 이어 붙인 생성 컬럼이고
     * 거기에 유일 인덱스가 걸려 있다. 값을 갈라 두면 실제 회원의 키와 부딪히지 않는다.
     */
    private static final String WARMUP_PROVIDER = "WARMUP";

    /*
     * 워밍업 회원의 식별자는 음수 대역을 쓴다.
     *
     * member_id 가 AUTO_INCREMENT 라 실제 회원은 늘 양수다. 음수를 명시로 넣으면 그 대역과 절대
     * 안 겹치고 AUTO_INCREMENT 카운터도 앞으로 안 밀린다.
     */
    private static final long ID_BAND = -1_000_000_000L;
    private static final long SLOT_SIZE = 1_000_000L;
    private static final int SLOT_COUNT = 100_000;

    private static final String INSERT_MEMBER_SQL = """
            INSERT INTO member (member_id, provider, provider_user_id, member_grade_id, status,
                                created_at, updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', NOW(6), NOW(6))
            """;

    private static final String DEFAULT_GRADE_SQL = """
            SELECT member_grade_id FROM member_grade WHERE is_default = TRUE LIMIT 1
            """;

    private static final String COUPON_SCOPE_SQL = """
            SELECT scope FROM coupon WHERE coupon_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final MemberCouponBulkRepository bulkRepository;
    private final CouponWarmupProperties properties;
    private final CouponIssueProperties issueProperties;
    private final TransactionTemplate transactionTemplate;

    public CouponWriteWarmup(JdbcTemplate jdbcTemplate,
                             MemberCouponBulkRepository bulkRepository,
                             CouponWarmupProperties properties,
                             CouponIssueProperties issueProperties,
                             PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.bulkRepository = bulkRepository;
        this.properties = properties;
        this.issueProperties = issueProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setTimeout((int) properties.writeTimeout().toSeconds());
    }

    /**
     * 데우고 되돌린다. 실패는 호출자가 아니라 여기서 삼킨다.
     *
     * @return 실제로 넣었다 되돌린 발급분 행 수. 한 라운드라도 실패하면 그 앞까지의 수다
     */
    public int warmUp() {
        if (properties.writeRows() <= 0) {
            return 0;
        }
        long startedAt = System.nanoTime();
        Long gradeId = queryOne(DEFAULT_GRADE_SQL, Long.class);
        String scope = queryOne(COUPON_SCOPE_SQL, String.class, properties.couponId());
        if (gradeId == null || scope == null) {
            log.warn("event=COUPON_WRITE_WARMUP_SKIPPED reason=fixtureMissing grade={} scope={}", gradeId, scope);
            return 0;
        }

        /*
         * 인스턴스마다 다른 자리를 쓴다.
         * 세 대가 동시에 데우면 같은 식별자를 두고 서로의 롤백을 기다리게 되는데, 자리를 갈라
         * 두면 그 대기가 없다. 되돌릴 값이라 자리가 겹쳐도 데이터가 깨지지는 않는다.
         */
        long idBase = ID_BAND - ThreadLocalRandom.current().nextInt(SLOT_COUNT) * SLOT_SIZE;

        int chunk = issueProperties.batchSize();
        int done = 0;
        while (done < properties.writeRows()) {
            int size = Math.min(chunk, properties.writeRows() - done);
            try {
                roundAndRollback(idBase, size, gradeId, CouponScope.valueOf(scope));
            } catch (Exception e) {
                log.warn("event=COUPON_WRITE_WARMUP_FAILED rows={} elapsedMs={}", done, elapsedMillis(startedAt), e);
                return done;
            }
            done += size;
        }
        log.info("event=COUPON_WRITE_WARMUP_DONE rows={} chunk={} elapsedMs={}",
                done, chunk, elapsedMillis(startedAt));
        return done;
    }

    /*
     * 한 라운드가 한 트랜잭션이다. 라운드를 나눠 두는 이유가 둘이다.
     *
     * 하나는 롤백이다. 되돌릴 행이 많을수록 서버가 언두를 되감는 시간이 길어지는데, 이 프로필의
     * socketTimeout 이 300 밀리초라 그 시간이 길면 드라이버가 먼저 연결을 끊는다.
     *
     * 다른 하나는 슬로 쿼리 로그다. long_query_time 이 1초라 그보다 긴 문장은 CloudWatch 로
     * 넘어가 부하 시험 분석을 더럽힌다. 라운드를 플러시 배치와 같은 크기로 자르면 한 문장이
     * 실제 플러시 한 번과 같아져 둘 다 자연히 지켜진다.
     */
    private void roundAndRollback(long idBase, int size, long gradeId, CouponScope scope) {
        transactionTemplate.executeWithoutResult(status -> {
            insertMembers(idBase, size, gradeId);
            bulkRepository.insertAll(tickets(idBase, size, scope));
            /*
             * 이 한 줄이 이 클래스의 전부다.
             * 여기까지 오는 동안 데우려던 것은 다 지났고, 남길 것은 하나도 없다.
             */
            status.setRollbackOnly();
        });
    }

    /*
     * 발급분이 fk_mc_member 를 만족하려면 회원 행이 먼저 있어야 한다.
     * 같은 트랜잭션 안이면 아직 커밋되지 않은 이 행도 외래 키 검사를 통과한다.
     */
    private void insertMembers(long idBase, int size, long gradeId) {
        jdbcTemplate.batchUpdate(INSERT_MEMBER_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setLong(1, idBase - i);
                ps.setString(2, WARMUP_PROVIDER);
                ps.setString(3, "warmup-" + (idBase - i));
                ps.setLong(4, gradeId);
            }

            @Override
            public int getBatchSize() {
                return size;
            }
        });
    }

    /*
     * issueLimit 을 size 로 둔다. chk_mc_issue_seq 가 순번이 1 이상 issueLimit 이하이기를
     * 요구하는데, 워밍업 쿠폰의 실제 수량은 1 이라 그 값을 그대로 쓰면 둘째 행부터 걸린다.
     * 이 값은 발급 시점 사본일 뿐이고 coupon 쪽과 외래 키로 묶여 있지 않다.
     */
    private List<IssueTicket> tickets(long idBase, int size, CouponScope scope) {
        List<IssueTicket> tickets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            tickets.add(IssueTicket.of(properties.couponId(), idBase - i, scope, size, i + 1));
        }
        return tickets;
    }

    private <T> T queryOne(String sql, Class<T> type, Object... args) {
        List<T> found = jdbcTemplate.queryForList(sql, type, args);
        return found.isEmpty() ? null : found.get(0);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
