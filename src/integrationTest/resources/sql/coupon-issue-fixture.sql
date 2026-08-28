-- 발급 행은 coupon 과 member 를 복합 외래 키로 참조한다. 그 두 행을 먼저 만든다.
-- member_grade_id 1 은 V4__seed_member_grades.sql 이 넣는다.

-- 이력이 발급 행을 참조하므로 이력을 먼저 지운다. 순서를 바꾸면 fk_mcsh_member_coupon 이 삭제를 막는다.
DELETE FROM member_coupon_status_history
 WHERE member_coupon_id IN (SELECT member_coupon_id FROM member_coupon WHERE coupon_id = 9001);
DELETE FROM member_coupon WHERE coupon_id = 9001;
DELETE FROM member WHERE member_id BETWEEN 9101 AND 9110;
DELETE FROM coupon WHERE coupon_id = 9001;

INSERT INTO coupon (coupon_id, name, scope, discount_type, discount_value,
                    min_order_amount, total_quantity, issued_quantity,
                    issue_start_at, issue_end_at, valid_from, valid_to, is_active,
                    created_at, updated_at)
VALUES (9001, '통합테스트 선착순 쿠폰', 'ORDER', 'AMOUNT', 1000,
        0, 100, 0,
        '2026-01-01 00:00:00', '2030-01-01 00:00:00', '2026-01-01', '2030-01-01', TRUE,
        NOW(6), NOW(6));

INSERT INTO member (member_id, provider_user_id, member_grade_id, status, created_at, updated_at)
VALUES (9101, 'it-coupon-9101', 1, 'ACTIVE', NOW(6), NOW(6)),
       (9102, 'it-coupon-9102', 1, 'ACTIVE', NOW(6), NOW(6)),
       (9103, 'it-coupon-9103', 1, 'ACTIVE', NOW(6), NOW(6)),
       (9104, 'it-coupon-9104', 1, 'ACTIVE', NOW(6), NOW(6)),
       (9105, 'it-coupon-9105', 1, 'ACTIVE', NOW(6), NOW(6));
