package com.freshmarket.coupon.domain.repository;

import java.util.List;
import java.util.Optional;

import com.freshmarket.coupon.domain.audit.CouponIssueCount;
import com.freshmarket.coupon.domain.audit.CouponSeqSpan;
import com.freshmarket.coupon.domain.audit.DuplicateIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 정합성 검증이 읽는 쿼리 모음이다. <b>여기에는 쓰기가 하나도 없다.</b>
 *
 * <p>검증은 재는 것이고 고치는 것은 회수 배치가 한다
 * ({@code docs/coupon/coupon.md} 10장). 섞으면 재실행 결과가 달라져 요구를 위반한다.
 *
 * <p>JPA 를 안 쓴다. 집계뿐이라 엔티티로 만들 것이 없고, 300만 행을 영속성 컨텍스트에
 * 올릴 이유는 더더욱 없다.
 *
 * <p><b>상관 서브쿼리를 쓰지 않는다.</b> 발급분마다 이력을 따로 찾으면 300만 번 도는 형태가 되어
 * 끝나지 않는다. 마지막 전이는 한 번에 모아 조인한다.
 */
@Repository
@RequiredArgsConstructor
public class CouponConsistencyRepository {

    /*
     * 쿠폰 한 장에 한 행이라 결과가 작다.
     * 카운터 대조와 한정 수량 대조가 같은 집계를 보므로 한 번만 훑는다.
     */
    private static final String ISSUE_COUNTS_SQL = """
            SELECT c.coupon_id, c.issued_quantity, c.total_quantity, COUNT(mc.member_coupon_id) AS actual
              FROM coupon c
              LEFT JOIN member_coupon mc ON mc.coupon_id = c.coupon_id
             GROUP BY c.coupon_id, c.issued_quantity, c.total_quantity
             ORDER BY c.coupon_id
            """;

    // 순번을 가진 것만 본다. 무제한 쿠폰은 issue_seq 가 NULL 이라 연속성을 물을 대상이 아니다
    private static final String SEQ_SPANS_SQL = """
            SELECT mc.coupon_id, MAX(mc.issue_seq) AS max_seq, COUNT(*) AS issued
              FROM member_coupon mc
             WHERE mc.issue_seq IS NOT NULL
             GROUP BY mc.coupon_id
             ORDER BY mc.coupon_id
            """;

    /*
     * uk_mc_coupon_member 와 열 순서가 같아 인덱스만 훑고 정렬이 따로 안 붙는다.
     * ORDER BY 를 명시하는 것은 재실행 결과가 같아야 해서다. 상한을 걸면서 순서를 안 정하면
     * 같은 데이터에서도 회차마다 다른 행이 잘려 나간다.
     */
    private static final String DUPLICATES_SQL = """
            SELECT coupon_id, member_id, COUNT(*) AS cnt
              FROM member_coupon
             GROUP BY coupon_id, member_id
            HAVING COUNT(*) > 1
             ORDER BY coupon_id, member_id
             LIMIT %d
            """.formatted(100);

    private static final String STATUS_HISTORY_MISMATCH_SQL = """
            SELECT COUNT(*)
              FROM member_coupon mc
              JOIN (SELECT member_coupon_id, MAX(member_coupon_status_history_id) AS last_id
                      FROM member_coupon_status_history
                     GROUP BY member_coupon_id) last_of
                ON last_of.member_coupon_id = mc.member_coupon_id
              JOIN member_coupon_status_history h
                ON h.member_coupon_status_history_id = last_of.last_id
             WHERE mc.status <> h.to_status
            """;

    // 이력이 한 줄도 없는 발급분이다. 어긋남으로 세지는 않고 값만 낸다
    private static final String WITHOUT_HISTORY_SQL = """
            SELECT COUNT(*)
              FROM member_coupon mc
             WHERE NOT EXISTS (SELECT 1 FROM member_coupon_status_history h
                                WHERE h.member_coupon_id = mc.member_coupon_id)
            """;

    // 쿠폰 하나만 즉시 확인할 때 쓴다. WHERE 로 좁혀도 LEFT JOIN 구조는 findIssueCounts()와 같다
    private static final String ISSUE_COUNT_SQL = """
            SELECT c.coupon_id, c.issued_quantity, c.total_quantity, COUNT(mc.member_coupon_id) AS actual
              FROM coupon c
              LEFT JOIN member_coupon mc ON mc.coupon_id = c.coupon_id
             WHERE c.coupon_id = ?
             GROUP BY c.coupon_id, c.issued_quantity, c.total_quantity
            """;

    // 쿠폰 하나의 순번만 읽는다. 한정 수량만큼만 나오므로 빈 자리는 이 목록에서 앱이 직접 계산한다
    private static final String ISSUE_SEQS_SQL = """
            SELECT issue_seq FROM member_coupon
             WHERE coupon_id = ? AND issue_seq IS NOT NULL
             ORDER BY issue_seq
            """;

    /*
     * 구멍이 있는지부터 가볍게 물을 때 쓴다. MAX() 하나만 집계하므로 findIssueSeqs()처럼
     * 순번을 전부 애플리케이션 메모리로 끌어오지 않는다. totalQuantity 에 상한이 없어 관리자가
     * 아주 큰 수량의 한정 쿠폰을 만들 수 있으므로, "구멍 없음"이 정상인 대부분의 호출에서
     * 이 가벼운 조회만으로 끝내는 것이 중요하다.
     */
    private static final String MAX_ISSUE_SEQ_SQL = """
            SELECT MAX(issue_seq) FROM member_coupon
             WHERE coupon_id = ? AND issue_seq IS NOT NULL
            """;

    private static final String DUPLICATE_MEMBER_COUNT_SQL = """
            SELECT COUNT(*) FROM (
                SELECT member_id FROM member_coupon
                 WHERE coupon_id = ?
                 GROUP BY member_id
                HAVING COUNT(*) > 1
            ) duplicated
            """;

    private final JdbcTemplate jdbcTemplate;

    /** 쿠폰 하나의 카운터, 한정 수량, 실제 발급 행 수를 읽는다. 행이 없으면 그 쿠폰이 없는 것이다. */
    public Optional<CouponIssueCount> findIssueCount(long couponId) {
        return jdbcTemplate.query(ISSUE_COUNT_SQL, (rs, rowNum) -> new CouponIssueCount(
                        rs.getLong("coupon_id"),
                        rs.getLong("issued_quantity"),
                        rs.getObject("total_quantity", Integer.class),
                        rs.getLong("actual")),
                couponId).stream().findFirst();
    }

    /** 쿠폰 하나에 나간 순번을 오름차순으로 읽는다. 무제한 쿠폰은 빈 목록이다. */
    public List<Integer> findIssueSeqs(long couponId) {
        return jdbcTemplate.query(ISSUE_SEQS_SQL, (rs, rowNum) -> rs.getInt("issue_seq"), couponId);
    }

    /** 쿠폰 하나의 가장 큰 순번만 읽는다. 발급이 없거나 무제한 쿠폰이면 비어 있다. */
    public Optional<Integer> findMaxIssueSeq(long couponId) {
        return Optional.ofNullable(jdbcTemplate.queryForObject(MAX_ISSUE_SEQ_SQL, Integer.class, couponId));
    }

    /** 쿠폰 하나에서 같은 회원이 둘 이상 받은 건수를 센다. */
    public long countDuplicateMembers(long couponId) {
        Long counted = jdbcTemplate.queryForObject(DUPLICATE_MEMBER_COUNT_SQL, Long.class, couponId);
        return counted == null ? 0L : counted;
    }

    /** 쿠폰마다 카운터, 한정 수량, 실제 발급 행 수를 한 행으로 읽는다. */
    public List<CouponIssueCount> findIssueCounts() {
        return jdbcTemplate.query(ISSUE_COUNTS_SQL, (rs, rowNum) -> new CouponIssueCount(
                rs.getLong("coupon_id"),
                rs.getLong("issued_quantity"),
                rs.getObject("total_quantity", Integer.class),
                rs.getLong("actual")));
    }

    /** 한정 쿠폰마다 가장 큰 순번과 실제 발급 행 수를 읽는다. 구멍 수는 둘의 차다. */
    public List<CouponSeqSpan> findSeqSpans() {
        return jdbcTemplate.query(SEQ_SPANS_SQL, (rs, rowNum) -> new CouponSeqSpan(
                rs.getLong("coupon_id"), rs.getInt("max_seq"), rs.getLong("issued")));
    }

    /** 한 회원이 같은 쿠폰을 둘 이상 받은 것을 찾는다. */
    public List<DuplicateIssue> findDuplicateIssues() {
        return jdbcTemplate.query(DUPLICATES_SQL, (rs, rowNum) -> new DuplicateIssue(
                rs.getLong("coupon_id"), rs.getLong("member_id"), rs.getLong("cnt")));
    }

    /** 마지막 전이가 현재 상태와 다른 발급분을 센다. R5 의 규율이 깨졌는지를 결과로 확인하는 자리다. */
    public long countStatusHistoryMismatches() {
        return count(STATUS_HISTORY_MISMATCH_SQL);
    }

    /** 이력이 한 줄도 없는 발급분을 센다. */
    public long countIssuesWithoutHistory() {
        return count(WITHOUT_HISTORY_SQL);
    }

    private long count(String sql) {
        Long counted = jdbcTemplate.queryForObject(sql, Long.class);
        return counted == null ? 0L : counted;
    }
}
