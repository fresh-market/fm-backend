package com.freshmarket.coupon.internal.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.freshmarket.coupon.internal.issue.IssueTicket;
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
 *
 * <p><b>시각은 앱이 아니라 DB 가 찍는다.</b> 발급 인스턴스가 여럿이라 앱 시계는 서로 어긋나고,
 * 그러면 {@code issue_seq} 가 매긴 순서와 {@code created_at} 이 말하는 순서가 달라진다. 선착순을
 * 나중에 증명할 근거가 둘인데 그 둘이 다른 이야기를 하게 된다. 순번 확보 스크립트가 앱 시계를
 * 마다하고 Redis {@code TIME} 을 받아 쓰는 것과 같은 이유다({@code coupon-issue-seq.lua}).
 *
 * <p>{@code rewriteBatchedStatements=true} 가 배치를 다중행 INSERT 문장 하나로 합치고, MySQL 은
 * {@code NOW(6)} 을 한 문장에서 한 번만 평가한다. <b>그래서 한 번의 플러시로 나간 행들이 모두
 * 같은 시각을 갖는다.</b> 앱이 행마다 시각을 만들던 이전 방식은 한 배치 안에서도 값이 흩어졌다.
 */
@Repository
@RequiredArgsConstructor
public class MemberCouponBulkRepository {

    private static final String INSERT_SQL = """
            INSERT INTO member_coupon
                (coupon_id, member_id, scope, issue_limit, issue_seq, status, issued_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'ISSUED', NOW(6), NOW(6), NOW(6))
            """;

    private static final String FIND_SEQ_SQL = """
            SELECT issue_seq FROM member_coupon WHERE coupon_id = ? AND member_id = ?
            """;

    /*
     * 자리 표시자 개수가 순번 개수에 따라 달라져 이 문장만 조립한다.
     * 값은 전부 파라미터로 넘어가고 조립되는 것은 물음표뿐이라 주입될 자리가 없다.
     */
    private static final String FIND_OWNERS_SQL = """
            SELECT member_id, issue_seq FROM member_coupon
             WHERE coupon_id = ? AND issue_seq IN (%s)
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
                bind(ps, batch.get(i));
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    public void insertOne(IssueTicket ticket) {
        jdbcTemplate.update(INSERT_SQL, ps -> bind(ps, ticket));
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

    /**
     * 이 순번들을 실제로 쥔 회원이다. {@code uk_mc_coupon_seq} 에 걸렸을 때 그 번호의 주인을
     * 찾아 확정 표시를 되살리려고 읽는다({@code docs/coupon/coupon.md} 3장).
     *
     * <p>그 위반이 곧 <b>이 순번을 쓰는 행이 DB 에 있다</b>는 증거라, 이 조회는 대개 넣은 만큼
     * 돌려준다. 회수가 잘못 짚은 건이 한 배치에 여럿 몰리므로 한 문장으로 묶어 읽는다.
     *
     * <p>{@code uk_mc_coupon_seq} 가 이 조회의 인덱스다. 유니크 인덱스라 순번 하나가 행 하나를
     * 바로 집는다.
     *
     * @return 순번 -> 그 번호를 쥔 회원. 행이 없는 순번은 결과에서 빠진다
     */
    public Map<Integer, Long> findOwners(long couponId, List<Integer> issueSeqs) {
        if (issueSeqs.isEmpty()) {
            return Map.of();
        }
        Object[] args = new Object[issueSeqs.size() + 1];
        args[0] = couponId;
        for (int i = 0; i < issueSeqs.size(); i++) {
            args[i + 1] = issueSeqs.get(i);
        }
        Map<Integer, Long> owners = new HashMap<>(issueSeqs.size());
        jdbcTemplate.query(FIND_OWNERS_SQL.formatted(placeholders(issueSeqs.size())),
                rs -> {
                    owners.put(rs.getInt("issue_seq"), rs.getLong("member_id"));
                },
                args);
        return owners;
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static void bind(PreparedStatement ps, IssueTicket ticket) throws SQLException {
        ps.setLong(1, ticket.couponId());
        ps.setLong(2, ticket.memberId());
        ps.setString(3, ticket.scope().name());
        ps.setInt(4, ticket.issueLimit());
        ps.setInt(5, ticket.issueSeq());
    }
}
