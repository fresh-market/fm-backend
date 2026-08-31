package com.freshmarket.coupon.internal.repository;

import java.util.List;

import com.freshmarket.coupon.internal.dto.AdminMemberCouponHistoryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 발급분 하나의 상태 전이 이력을 읽는다.
 *
 * member_coupon_status_history 는 엔티티로 두지 않는다(MemberCouponRepository 상단 설명과 같은
 * 이유). 이력은 상태를 바꾸는 그 트랜잭션 안에서 네이티브 INSERT 로만 들어가는 집합이라
 * JPA 로 매핑할 쓰기 경로가 없다.
 */
@Repository
@RequiredArgsConstructor
public class MemberCouponHistoryRepository {

    private static final String FIND_BY_MEMBER_COUPON_ID_SQL = """
            SELECT from_status, to_status, reason, changed_by, created_at
              FROM member_coupon_status_history
             WHERE member_coupon_id = ?
             ORDER BY member_coupon_status_history_id ASC
            """;

    private final JdbcTemplate jdbcTemplate;

    /** 이 발급분이 지금까지 거친 전이를 일어난 순서대로 읽는다. */
    public List<AdminMemberCouponHistoryEntry> findByMemberCouponId(long memberCouponId) {
        return jdbcTemplate.query(FIND_BY_MEMBER_COUPON_ID_SQL, (rs, rowNum) -> new AdminMemberCouponHistoryEntry(
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("reason"),
                        (Long) rs.getObject("changed_by"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                memberCouponId);
    }
}
