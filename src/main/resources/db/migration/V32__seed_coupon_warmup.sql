-- =====================================================================
-- 워밍업 전용 쿠폰
-- =====================================================================
-- 기동 직후 CouponWarmupRunner 가 이 쿠폰으로 발급 요청을 흘려 JIT 을 데운다.
-- 차가운 JVM 으로 이벤트를 열면 p99 가 4.69초까지 간다 (2026-08-31 부하 시험).
--
-- total_quantity 를 1 로 둔다. 0 은 못 넣는다.
-- chk_coupon_quantity 가 "total_quantity IS NULL OR total_quantity > 0" 을 요구하기 때문이다.
--
-- 그러면서도 실제 발급은 안 난다. 러너가 기동 때 이 쿠폰의 Redis 카운터를 소진 상태로 세워
-- 두므로, 요청이 순번 확보까지 갔다가 소진으로 끝나고 member_coupon 에 아무것도 안 쓴다.
-- 쓰게 두면 fk_mc_member 때문에 워밍업용 회원 행까지 심어야 하고, 그 행들이 운영 데이터에
-- 섞인다. 데우는 것이 목적이지 발급이 목적이 아니므로 여기서 멈춘다.
--
-- is_active 는 TRUE 다. 관리자 API 로 여는 이벤트 쿠폰과 달리 이 쿠폰은 늘 열려 있어야
-- 기동할 때마다 워밍업이 돈다. 재고가 0 이라 실제로 발급되지는 않는다.
--
-- issue_end_at 을 멀리 둔다. 지나면 자격 검사에서 먼저 걸려 순번 확보까지 못 간다.
--
-- 번호를 1000000 으로 잡은 이유가 있다. 이 저장소는 999999 를 "없는 것" 을 뜻하는 관용 번호로
-- 써 왔고(SecurityAuthorizationIntegrationTest 가 "없는 쿠폰이라 404" 로 그 번호를 쓴다),
-- 거기에 실재하는 행을 심으면 그 전제가 조용히 깨진다. 부하 시험이 쓰는 900001 도 피한다.

INSERT INTO coupon (coupon_id, name, scope, discount_type, discount_value,
                    min_order_amount, total_quantity, issued_quantity,
                    issue_start_at, issue_end_at, valid_from, valid_to, is_active,
                    created_at, updated_at)
VALUES (1000000, '워밍업 전용(발급되지 않음)', 'ITEM', 'RATE', 1,
        0, 1, 0,
        '2020-01-01 00:00:00.000000', '2099-12-31 23:59:59.000000',
        '2020-01-01', '2099-12-31', TRUE,
        NOW(6), NOW(6));
