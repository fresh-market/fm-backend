package com.freshmarket.coupon.domain.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.freshmarket.coupon.domain.issue.IssueTicket;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 발급 행을 벌크로 쓴다. JPA 를 쓰지 않는 이유는 이 경로가 순수한 쓰기뿐이라 영속성 컨텍스트가
 * 할 일이 없어서다. 조회도 변경 감지도 없는 자리에 1차 캐시를 태울 값이 없다.
 *
 * <p>{@code status} 와 시각을 SQL 안에서 박는다. 발급 시점의 값이 행마다 다를 이유가 없고,
 * 파라미터를 줄이면 배치가 한 문장으로 압축될 때 문장이 짧아진다.
 */
@Repository
@RequiredArgsConstructor
public class MemberCouponBulkRepository {

    private static final String INSERT_SQL = """
            INSERT INTO member_coupon
                (coupon_id, member_id, scope, issue_limit, issue_seq, status, issued_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'ISSUED', ?, ?, ?)
            """;

    private static final String FIND_SEQ_SQL = """
            SELECT issue_seq FROM member_coupon WHERE coupon_id = ? AND member_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 배치를 한 번에 쓴다.
     *
     * <p>한 행이라도 제약에 걸리면 문장 전체가 실패하고 아무것도 안 들어간다. 어느 행이 문제인지는
     * 여기서 알 수 없으므로, 가려내는 것은 호출자가 {@link #insertOne} 으로 다시 넣어 한다.
     */
    public void insertAll(List<IssueTicket> batch) {
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                bind(ps, batch.get(i), LocalDateTime.now());
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    public void insertOne(IssueTicket ticket) {
        jdbcTemplate.update(INSERT_SQL, ps -> bind(ps, ticket, LocalDateTime.now()));
    }

    /**
     * 이 회원이 이미 갖고 있는 순번이다. {@code uk_mc_coupon_member} 에 걸렸을 때 어느 번호로
     * 매핑을 고칠지 알아내려고 읽는다.
     *
     * @return 행이 없으면 empty. 그때는 순번 쪽 제약에 걸린 것이다
     */
    public Optional<Integer> findIssuedSeq(long couponId, long memberId) {
        List<Integer> found = jdbcTemplate.queryForList(FIND_SEQ_SQL, Integer.class, couponId, memberId);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    private static void bind(PreparedStatement ps, IssueTicket ticket, LocalDateTime now) throws SQLException {
        Timestamp at = Timestamp.valueOf(now);
        ps.setLong(1, ticket.couponId());
        ps.setLong(2, ticket.memberId());
        ps.setString(3, ticket.scope().name());
        ps.setInt(4, ticket.issueLimit());
        ps.setInt(5, ticket.issueSeq());
        ps.setTimestamp(6, at);
        ps.setTimestamp(7, at);
        ps.setTimestamp(8, at);
    }
}
