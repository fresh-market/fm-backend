package com.freshmarket.coupon.domain.repository;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis 를 다시 세울 때 쓰는 읽기다. 이 쿠폰으로 실제 발급된 회원과 순번을 전부 가져온다.
 *
 * <p>JPA 를 안 쓰는 이유는 읽는 것이 두 칸뿐이어서다. 엔티티로 만들면 안 쓰는 컬럼까지 실어
 * 오고 1차 캐시에 만 건이 쌓인다.
 *
 * <p>한 번에 다 가져오는 것이 맞다. 재고가 만 장이라 행 수의 상한이 그 값이고, 이 조회는
 * 이벤트당 많아야 몇 번 도는 복구 경로다. 쪼개 읽으면 그 사이에 들어온 쓰기 때문에 순번이
 * 이가 빠진 것처럼 보여, 없는 구멍을 만들어 낸다.
 */
@Repository
@RequiredArgsConstructor
public class MemberCouponSeqRepository {

    private static final String FIND_SEQS_SQL = """
            SELECT member_id, issue_seq FROM member_coupon WHERE coupon_id = ? ORDER BY issue_seq
            """;

    private final JdbcTemplate jdbcTemplate;

    /** 회원과 순번의 짝이다. 순번 오름차순이다. */
    public record IssuedSeq(long memberId, int issueSeq) {
    }

    public List<IssuedSeq> findIssuedSeqs(long couponId) {
        return jdbcTemplate.query(FIND_SEQS_SQL,
                (rs, rowNum) -> new IssuedSeq(rs.getLong(1), rs.getInt(2)),
                couponId);
    }
}
