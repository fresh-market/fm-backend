/*
 * 쿠폰 정책은 한 번 만들면 바뀌지 않으므로 발급 시점에 조건을 복사할 이유가 없다.
 * 값은 coupon 한 곳에 두고 member_coupon 은 fk_mc_coupon 으로 그 행을 참조한다.
 * 근거는 docs/code-architecture/schema-design-rationale.md 5장에 있다.
 */

-- 지우는 컬럼을 쓰는 CHECK 을 먼저 뗀다. 같은 검사가 coupon 쪽에 chk_coupon_* 로 남아 있다.
ALTER TABLE member_coupon
    DROP CONSTRAINT chk_mc_discount_type,
    DROP CONSTRAINT chk_mc_values,
    DROP CONSTRAINT chk_mc_rate,
    DROP CONSTRAINT chk_mc_max_discount,
    DROP CONSTRAINT chk_mc_valid_period;

/*
 * scope 와 issue_limit 은 남긴다.
 * scope 는 fk_mc_coupon 과 fk_order_coupon_scope 가 쓰는 복합 외래 키의 한 칸이고,
 * issue_limit 은 chk_mc_issue_seq 가 보는 값이라 참조로 바꾸면 그 CHECK 을 걸 수 없다.
 */
ALTER TABLE member_coupon
    DROP COLUMN coupon_name,
    DROP COLUMN discount_type,
    DROP COLUMN discount_value,
    DROP COLUMN max_discount_amount,
    DROP COLUMN min_order_amount,
    DROP COLUMN valid_from,
    DROP COLUMN valid_to;
