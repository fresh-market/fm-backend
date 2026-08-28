-- 부하 시험용 선착순 쿠폰을 만든다.
--
-- Flyway 마이그레이션이 아니다. 운영에서 돌면 안 되는 데이터라 손으로 실행한다.
--
--   docker exec -i freshmarket-mysql mysql --default-character-set=utf8mb4 \
--     -ufreshmarket -pfreshmarket freshmarket < loadtest/seed-coupon.sql
--
-- 문자셋을 안 주면 마지막 확인 SELECT 의 한글 별칭에서 구문 오류가 난다.
--
-- 요구사항이 "재고 10,000장에 20,000명 동시 요청" 이라 총량을 1만으로 둔다.
-- 혜택은 기획을 따라 ITEM 범위의 정률 30% 다.

/*
 * coupon_id 를 직접 준다.
 * k6 시나리오가 경로에 이 값을 박아 쓰고, AUTO_INCREMENT 에 맡기면 실행할 때마다 달라진다.
 */
SET @coupon_id = 900001;

/*
 * 다시 돌릴 수 있어야 한다.
 * 발급분과 그 이력을 먼저 지우는 것은 fk_mc_coupon 과 fk_mcsh_member_coupon 때문이다.
 */
DELETE FROM member_coupon_status_history
 WHERE member_coupon_id IN (SELECT member_coupon_id FROM member_coupon WHERE coupon_id = @coupon_id);
DELETE FROM member_coupon WHERE coupon_id = @coupon_id;
DELETE FROM coupon WHERE coupon_id = @coupon_id;

/*
 * is_active 는 FALSE 로 둔다.
 * 발급 스위치를 켜는 것은 관리자 API 의 일이고, 그 API 가 Redis 카운터도 함께 세운다.
 * 여기서 TRUE 로 넣으면 카운터 없는 Redis 를 요청이 쳐서 시험이 통째로 준비 안 됨으로 끝난다.
 *
 * issue_end_at 을 반드시 넣는다. 마감이 없으면 선착순 쿠폰이 아니라 열리지 않는다.
 *
 * 발급 창을 하루씩 앞뒤로 벌리는 것은 시계 때문이다. NOW(6) 는 DB 서버 시각이고 발급 판정은
 * 앱 시각으로 한다. 컨테이너가 UTC 이고 앱이 KST 면 그 사이가 9시간 벌어져, 창을 몇 시간으로
 * 잡으면 넣자마자 이미 지난 이벤트가 된다.
 */
INSERT INTO coupon (coupon_id, name, scope, discount_type, discount_value,
                    min_order_amount, total_quantity, issued_quantity,
                    issue_start_at, issue_end_at, valid_from, valid_to, is_active,
                    created_at, updated_at)
VALUES (@coupon_id, '부하시험 선착순 30%', 'ITEM', 'RATE', 30,
        0, 10000, 0,
        DATE_SUB(NOW(6), INTERVAL 1 DAY), DATE_ADD(NOW(6), INTERVAL 1 DAY),
        DATE_SUB(CURDATE(), INTERVAL 1 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), FALSE,
        NOW(6), NOW(6));

SELECT coupon_id, total_quantity, issued_quantity, is_active, issue_start_at, issue_end_at
  FROM coupon WHERE coupon_id = @coupon_id;
