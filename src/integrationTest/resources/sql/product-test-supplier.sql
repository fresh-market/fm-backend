-- supplier_id 를 명시적으로 고정한다.
-- InnoDB 는 롤백된 행의 auto_increment 값을 되돌리지 않아, 이 값을 지정하지 않으면
-- 테스트를 실행할 때마다 실제 배정되는 id 가 달라진다.
INSERT INTO supplier (supplier_id, name, contact, created_at, updated_at)
VALUES (999999, '테스트공급처', NULL, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE name = VALUES(name);