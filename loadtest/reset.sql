-- 부하 시험을 다시 돌리기 전에 상태를 되돌린다.
--
--   docker exec -i freshmarket-mysql mysql --default-character-set=utf8mb4 \
--     -ufreshmarket -pfreshmarket freshmarket < loadtest/reset.sql
--
-- 이 파일은 DB 만 되돌린다. Redis 카운터는 관리자 API 의 이벤트 열기가 다시 세운다.
-- is_active 를 FALSE 로 내리는 것이 그래서 필요하다. 켜져 있으면 여는 API 가 "이미 열렸다" 로
-- 그냥 돌아가고, 지난 회차의 카운터와 매핑이 그대로 남는다.

SET @coupon_id = 900001;

DELETE FROM member_coupon_status_history
 WHERE member_coupon_id IN (SELECT member_coupon_id FROM member_coupon WHERE coupon_id = @coupon_id);
DELETE FROM member_coupon WHERE coupon_id = @coupon_id;

UPDATE coupon
   SET is_active = FALSE, issued_quantity = 0,
       issue_start_at = DATE_SUB(NOW(6), INTERVAL 1 DAY),
       issue_end_at = DATE_ADD(NOW(6), INTERVAL 1 DAY),
       updated_at = NOW(6)
 WHERE coupon_id = @coupon_id;

SELECT coupon_id, total_quantity, issued_quantity, is_active FROM coupon WHERE coupon_id = @coupon_id;
