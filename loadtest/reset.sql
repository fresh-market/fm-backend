-- 부하 시험을 다시 돌리기 전에 상태를 되돌린다.
--
--   docker exec -i freshmarket-mysql mysql --default-character-set=utf8mb4 \
--     -ufreshmarket -pfreshmarket freshmarket < loadtest/reset.sql
--
-- 이 파일은 DB 만 되돌린다. Redis 카운터는 관리자 API 의 이벤트 열기가 다시 세운다.
-- is_active 를 FALSE 로 내리는 것이 그래서 필요하다. 켜져 있으면 여는 API 가 "이미 열렸다" 로
-- 그냥 돌아가고, 지난 회차의 카운터와 매핑이 그대로 남는다.

-- 부르는 쪽이 @coupon_id_in 으로 다른 값을 줄 수 있다. 근거는 seed-coupon.sql 주석에 있다.
SET @coupon_id = COALESCE(@coupon_id_in, 900001);

DELETE FROM member_coupon_status_history
 WHERE member_coupon_id IN (SELECT member_coupon_id FROM member_coupon WHERE coupon_id = @coupon_id);
DELETE FROM member_coupon WHERE coupon_id = @coupon_id;

UPDATE coupon
   SET is_active = FALSE, issued_quantity = 0,
       issue_start_at = DATE_SUB(NOW(6), INTERVAL 1 DAY),
       issue_end_at = DATE_ADD(NOW(6), INTERVAL 1 DAY),
       updated_at = NOW(6)
 WHERE coupon_id = @coupon_id;

/*
 * 시험 쿠폰 전체를 내린다. 이 회차의 것만 내리면 안 된다.
 *
 * 회차마다 번호를 바꾸면 앞 회차 쿠폰이 열린 채로 쌓인다. 그러면 재연결 한 번에 재건이 그
 * 전부에 대해 뜨고(CouponSeqReconnectWatcher 가 findOpenLimitedEvents 로 열린 이벤트를 모두
 * 찾는다), 재건마다 플러시를 멈추므로 이 회차의 발급이 반복해서 중단된다.
 *
 * 2026-10-10 L-3 회차에서 그것이 났다. 본 쿠폰이 900007 인데 재건이 900001 부터 900006 까지
 * 다섯 개에 떴고, 재시도 20건과 실패 10건을 내는 동안 명부가 1명으로 밀려 결번 6 이 생겼다.
 * 앞선 L-1 과 L-2 는 쿠폰이 덜 쌓여 있어 결번 0 이었다
 * (docs/coupon/budget-breakdown-2026-10-10.md).
 *
 * 데이터를 지우는 것은 위처럼 이 회차 쿠폰만 한다. 앞 회차의 발급 이력은 다음 회차의 정합성
 * 검산 대상이 아니므로 지울 이유가 없고, 지우면 그만큼 느려진다.
 */
UPDATE coupon SET is_active = FALSE, updated_at = NOW(6)
 WHERE coupon_id >= 900000 AND is_active = TRUE;

SELECT coupon_id, total_quantity, issued_quantity, is_active FROM coupon WHERE coupon_id >= 900000;
