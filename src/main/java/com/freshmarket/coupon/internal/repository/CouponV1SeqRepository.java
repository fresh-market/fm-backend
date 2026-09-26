package com.freshmarket.coupon.internal.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * v1 브랜치 전용. 순번을 DB 조건부 UPDATE 로 받는다.
 *
 * v2 부터는 Redis INCR 이 이 일을 한다. v1 은 추가 인프라가 0 인 기준선이라 DB 하나로 판정한다
 * (docs/coupon/coupon.md 6장).
 *
 * 이 브랜치는 부하 시험 비교용이고 병합 대상이 아니다.
 */
@Repository
@RequiredArgsConstructor
public class CouponV1SeqRepository {

    /*
     * 한 문장으로 재고를 보고 올린다. 이것이 v1 의 판정이다.
     *
     * total_quantity 가 NULL 이면 무제한 쿠폰이라 조건이 항상 참이다. 선착순은 값이 있어
     * issued_quantity 가 그 값에 닿으면 갱신 행이 0 이 되고 그것이 소진이다.
     *
     * 이 UPDATE 가 그 행에 배타 락을 건다. 그래서 같은 쿠폰의 요청이 전부 한 줄로 선다.
     * v1 의 성능이 여기서 정해지고, v2 가 카운터를 Redis 로 옮겨 이 줄을 없앤다.
     *
     * 거절되는 쪽도 이 락을 기다린다는 것이 v1 의 핵심 약점이다. 판정 자체가 락 뒤에 있어서다.
     */
    private static final String CLAIM_SQL = """
            UPDATE coupon
               SET issued_quantity = issued_quantity + 1
             WHERE coupon_id = ?
               AND (total_quantity IS NULL OR issued_quantity < total_quantity)
            """;

    /*
     * 방금 올린 값을 읽는다. 위 UPDATE 와 같은 트랜잭션에서만 뜻이 있다.
     *
     * MySQL 에 UPDATE ... RETURNING 이 없어 두 문장으로 나뉜다. 그 사이에 남이 끼어들 수는
     * 없다. 위 UPDATE 가 잡은 배타 락을 이 트랜잭션이 커밋까지 쥐고 있다.
     */
    private static final String READ_SQL = "SELECT issued_quantity FROM coupon WHERE coupon_id = ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 순번 하나를 확보한다. 호출자가 트랜잭션을 열어 둔 상태여야 한다.
     *
     * @return 확보한 순번. 재고가 없으면 0
     */
    public int claim(long couponId) {
        if (jdbcTemplate.update(CLAIM_SQL, couponId) == 0) {
            return 0;
        }
        Integer seq = jdbcTemplate.queryForObject(READ_SQL, Integer.class, couponId);
        return seq == null ? 0 : seq;
    }
}
