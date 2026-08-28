-- 폐기 이력(stock_movement.admin_id)이 FK 로 admin 을 참조해, 폐기가 얽힌 테스트에 관리자가 필요하다.
-- admin_id 를 명시적으로 고정하는 이유는 supplier 픽스처와 같다 — InnoDB 는 롤백된 행의
-- auto_increment 값을 되돌리지 않아, 지정하지 않으면 실행할 때마다 배정되는 id 가 달라진다.
INSERT INTO admin (admin_id, login_id, password_hash, name, role, status, created_at, updated_at)
VALUES (999999, 'test-admin', 'not-a-real-hash', '테스트관리자', 'ADMIN', 'ACTIVE', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE name = VALUES(name);
