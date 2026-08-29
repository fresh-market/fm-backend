/*
 * 쿠폰 정책은 한 번 만들면 바뀌지 않으므로 발급 시점에 조건을 복사할 이유가 없다.
 * 값은 coupon 한 곳에 두고 member_coupon 은 fk_mc_coupon 으로 그 행을 참조한다.
 * 근거는 docs/code-architecture/schema-design-rationale.md 5장에 있다.
 */

/*
 * 두 문장 모두 알고리즘을 명시한다. member_coupon 은 선착순 발급분이 쌓이는 표라 크게 자란다.
 * 명시하지 않으면 MySQL 이 알아서 고르고, 고를 것이 없으면 조용히 COPY 로 표를 통째로 다시 만든다.
 * 명시하면 MySQL 이 그 알고리즘으로 못 할 때 실행 대신 오류를 내므로, 운영이 아니라 여기서 막힌다.
 */

-- 지우는 컬럼을 쓰는 CHECK 을 먼저 뗀다. 같은 검사가 coupon 쪽에 chk_coupon_* 로 남아 있다.
-- MySQL 은 CHECK 을 뗄 때 표를 다시 만들지 않으므로 다른 트랜잭션이 그동안 계속 읽고 쓴다.
ALTER TABLE member_coupon
    DROP CONSTRAINT chk_mc_discount_type,
    DROP CONSTRAINT chk_mc_values,
    DROP CONSTRAINT chk_mc_rate,
    DROP CONSTRAINT chk_mc_max_discount,
    DROP CONSTRAINT chk_mc_valid_period,
    ALGORITHM=INPLACE, LOCK=NONE;

/*
 * scope 와 issue_limit 은 남긴다.
 * scope 는 fk_mc_coupon 과 fk_order_coupon_scope 가 쓰는 복합 외래 키의 한 칸이고,
 * issue_limit 은 chk_mc_issue_seq 가 보는 값이라 참조로 바꾸면 그 CHECK 을 걸 수 없다.
 *
 * 일곱을 한 문장에 두는 것이 알고리즘 때문에도 맞다. MySQL 은 INSTANT 연산마다 행 버전을
 * 하나씩 쌓고 예순넷을 넘기면 그때부터 표를 다시 만드는데, 한 문장이면 버전을 하나만 쓴다.
 * LOCK 절은 못 붙인다. MySQL 은 INSTANT 에 LOCK=NONE 을 함께 주면 ERROR 1221 로 거절한다.
 */
ALTER TABLE member_coupon
    DROP COLUMN coupon_name,
    DROP COLUMN discount_type,
    DROP COLUMN discount_value,
    DROP COLUMN max_discount_amount,
    DROP COLUMN min_order_amount,
    DROP COLUMN valid_from,
    DROP COLUMN valid_to,
    ALGORITHM=INSTANT;
