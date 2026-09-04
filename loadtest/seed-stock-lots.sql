-- 캠페인 대상 배치와 소비기한 인덱스를 재기 위한 재고 더미데이터를 만든다.
--
-- Flyway 마이그레이션이 아니다. 운영에서 돌면 안 되는 데이터라 손으로 실행한다.
--
--   docker exec -i freshmarket-mysql mysql --default-character-set=utf8mb4 \
--     -ufreshmarket -pfreshmarket freshmarket < loadtest/seed-stock-lots.sql
--
-- 두 가지를 재려고 만든다.
--
--   1. idx_lot_expiry_date(V31) 가 실제로 이득인가
--      배치가 소비기한 4일 폭으로 후보를 찾는데, 인덱스가 없으면 stock_lot 전체를 훑는다.
--      그 차이는 "만료된 로트가 지워지지 않고 쌓인" 상태라야 드러난다. 그래서 만료 로트를
--      일부러 많이 만든다 — 필요한 4일 폭은 일정한데 표만 커지는 상황을 재현하는 것이 요점이다.
--
--   2. 배치가 실제로 얼마나 걸리는가 (SchedulerLoggingAspect 의 durationMs)
--      관리자 재확정을 앱 인스턴스에서 돌려도 되는지 판단하는 근거다.
--
-- 부하 시험용 데이터(seed-dummy-data.sql)와 쓰임이 다르다. 저쪽은 쿠폰 발급 정합성이고
-- 이쪽은 재고 조회다.

/*
 * 손으로 만든 데이터와 안 겹치게 접두어를 붙인다.
 * 다시 돌릴 수 있게 지우고 시작하므로 접두어가 곧 이 스크립트의 소유 범위다.
 */
SET @option_count = 2000;    -- 상품 옵션 수
SET @lots_per_option = 50;   -- 옵션당 로트 수 → stock_lot 10만 행

-- ---------------------------------------------------------------- 다시 돌릴 수 있게 지운다
-- 참조하는 쪽부터 지운다. campaign_target_lot 이 stock_lot 을 FK 로 잡고 있어
-- 배치를 한 번이라도 돌린 뒤에는 이것을 먼저 비워야 로트를 지울 수 있다
DELETE FROM campaign_target_lot;
DELETE m FROM stock_movement m
  JOIN stock_lot l ON l.stock_lot_id = m.stock_lot_id
  JOIN product_option o ON o.product_option_id = l.product_option_id
  JOIN product p ON p.product_id = o.product_id
 WHERE p.product_code LIKE 'DUMMY-%';
DELETE l FROM stock_lot l
  JOIN product_option o ON o.product_option_id = l.product_option_id
  JOIN product p ON p.product_id = o.product_id
 WHERE p.product_code LIKE 'DUMMY-%';
DELETE o FROM product_option o
  JOIN product p ON p.product_id = o.product_id
 WHERE p.product_code LIKE 'DUMMY-%';
DELETE FROM product WHERE product_code LIKE 'DUMMY-%';
DELETE FROM supplier WHERE name = '더미 공급사';
DELETE FROM admin WHERE login_id = 'dummy-admin';

-- ---------------------------------------------------------------- 1부터 N 까지의 수
/*
 * 재귀 CTE 로 내려가면 느리다. 자리수 표를 교차 조인하는 쪽이 훨씬 빠르다.
 * digits 를 임시 표로 두면 MySQL 이 한 쿼리에서 같은 임시 표를 두 번 열지 못해
 * 자기 조인하는 아래 문장이 "Can't reopen table" 로 실패한다. 다 쓰고 지운다.
 */
DROP TABLE IF EXISTS _seed_digits;
CREATE TABLE _seed_digits (d INT);
INSERT INTO _seed_digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

DROP TEMPORARY TABLE IF EXISTS nums;
CREATE TEMPORARY TABLE nums (n INT PRIMARY KEY);
INSERT INTO nums (n)
SELECT a.d + b.d*10 + c.d*100 + e.d*1000 + f.d*10000 + 1
  FROM _seed_digits a, _seed_digits b, _seed_digits c, _seed_digits e, _seed_digits f;

DROP TABLE _seed_digits;

-- ---------------------------------------------------------------- 참조 데이터
INSERT INTO supplier (name, created_at, updated_at) VALUES ('더미 공급사', NOW(6), NOW(6));
SET @supplier_id = LAST_INSERT_ID();

/*
 * 폐기 이력에 처리자가 필요하다 (chk_movement_disposal).
 *
 * password_hash 는 무작위 값을 BCrypt 로 돌린 것이다. 원문을 아무도 모르므로 이 계정으로는
 * 로그인할 수 없다. 로컬 더미라도 평문을 넣지 않는다 (SEC-4-01).
 */
INSERT INTO admin (login_id, password_hash, name, role, status, created_at, updated_at)
VALUES ('dummy-admin', '$2a$10$zbA7lXuybCcK2cv2QXOr5enQjDgvj2VYR5ySvOgwTkHQcTIGwkEQq',
        '더미관리자', 'ADMIN', 'ACTIVE', NOW(6), NOW(6));
SET @admin_id = LAST_INSERT_ID();

SET @category_id = (SELECT category_id FROM category ORDER BY category_id LIMIT 1);

-- ---------------------------------------------------------------- 상품과 옵션
/*
 * sale_available_days_from_expiry 를 10 으로 둔다. 배치의 SALE_CLOSE_DAYS 와 같은 값이라
 * 판매 마감 기한 계산이 운영과 맞는다.
 */
INSERT INTO product (product_code, request_id, name, category_id, supplier_id, sale_status, storage_type,
                     sale_available_days_from_expiry, created_at, updated_at)
SELECT CONCAT('DUMMY-', n), CONCAT('dummy-req-', n), CONCAT('더미상품 ', n), @category_id, @supplier_id,
       'ON_SALE', 'COLD', 10, NOW(6), NOW(6)
  FROM nums WHERE n <= @option_count;

INSERT INTO product_option (product_id, name, price, sale_status, created_at, updated_at)
SELECT p.product_id, '1kg', 9900, 'ON_SALE', NOW(6), NOW(6)
  FROM product p WHERE p.product_code LIKE 'DUMMY-%';

-- ---------------------------------------------------------------- 로트
/*
 * 소비기한을 넓게 흩는다. 배치가 보는 구간은 D+10 ~ D+13 뿐이고 나머지는 전부 "훑기만 하고
 * 버리는" 행이다. 그 비율이 인덱스의 값을 정한다.
 *
 *   n % 50 <  3   → D+10 ~ D+13   후보 구간 (6%)
 *   n % 50 <  20  → D+14 ~ D+30   아직 임박 전
 *   나머지        → 과거          이미 만료된 로트가 쌓인 상태
 *
 * 과거 로트를 많이 두는 것이 요점이다. 만료 처리는 소비기한이 지난 뒤에 도는데 행을 지우지는
 * 않으므로, 영업이 길어질수록 이쪽만 계속 는다. 인덱스가 끊으려는 증가가 바로 이것이다.
 *
 * chk_lot_status_qty 가 AVAILABLE 이 아닌 로트의 available_qty 를 0 으로 강제한다.
 * chk_lot_qty 는 available_qty <= initial_qty 를 본다.
 */
INSERT INTO stock_lot (product_option_id, request_id, received_date, expiry_date,
                       initial_qty, available_qty, status, created_at, updated_at)
SELECT o.product_option_id,
       CONCAT('dummy-lot-', o.product_option_id, '-', n.n),
       CURDATE() - INTERVAL 90 DAY,  -- 가장 오래된 만료 로트(D-59)보다 앞서야 한다 (chk_lot_expiry_date)
       CASE
           WHEN n.n % 50 < 3  THEN CURDATE() + INTERVAL (10 + (n.n % 4)) DAY
           WHEN n.n % 50 < 20 THEN CURDATE() + INTERVAL (14 + (n.n % 17)) DAY
           ELSE CURDATE() - INTERVAL (n.n % 60) DAY
       END,
       100,
       CASE WHEN n.n % 50 < 20 THEN 30 + (n.n % 60) ELSE 0 END,
       CASE WHEN n.n % 50 < 20 THEN 'AVAILABLE' ELSE 'EXPIRED' END,
       NOW(6), NOW(6)
  FROM product_option o
  JOIN product p ON p.product_id = o.product_id
  JOIN nums n ON n.n <= @lots_per_option
 WHERE p.product_code LIKE 'DUMMY-%';

-- ---------------------------------------------------------------- 폐기 이력
/*
 * 후보 구간 로트의 일부에 폐기 이력을 남긴다. 소진율이 폐기분을 빼고 계산되는지,
 * 그리고 회수품(RETURNED)이 분모에서 빠지지 않는지를 이 데이터가 태운다.
 *
 * chk_movement_delta 가 유형별 방향을 강제한다.
 *   RETURNED 가 아닌 폐기 : qty_after = qty_before - quantity
 *   RETURNED             : qty_after = qty_before        (재고를 줄이지 않는다)
 *
 * available_qty 는 이미 위에서 정해 두었으므로 이력의 앞뒤 값만 규칙에 맞게 채운다.
 * 배치는 수량 합계만 보고 앞뒤 값은 안 보므로 집계에는 영향이 없다.
 */
INSERT INTO stock_movement (stock_lot_id, request_id, movement_type, quantity,
                            qty_before, qty_after, admin_id, disposal_reason, reason, created_at)
SELECT l.stock_lot_id,
       CONCAT('dummy-mv-', l.stock_lot_id),
       'DISPOSE', 10,
       l.available_qty + 10, l.available_qty,
       @admin_id, 'DAMAGED', '더미 파손 폐기', NOW(6)
  FROM stock_lot l
  JOIN product_option o ON o.product_option_id = l.product_option_id
  JOIN product p ON p.product_id = o.product_id
 WHERE p.product_code LIKE 'DUMMY-%'
   AND l.status = 'AVAILABLE'
   AND l.stock_lot_id % 3 = 0;

-- 회수품 폐기. 재고를 줄이지 않으므로 앞뒤가 같다
INSERT INTO stock_movement (stock_lot_id, request_id, movement_type, quantity,
                            qty_before, qty_after, admin_id, disposal_reason, reason, created_at)
SELECT l.stock_lot_id,
       CONCAT('dummy-mv-ret-', l.stock_lot_id),
       'DISPOSE', 5,
       l.available_qty, l.available_qty,
       @admin_id, 'RETURNED', '더미 회수품 폐기', NOW(6)
  FROM stock_lot l
  JOIN product_option o ON o.product_option_id = l.product_option_id
  JOIN product p ON p.product_id = o.product_id
 WHERE p.product_code LIKE 'DUMMY-%'
   AND l.status = 'AVAILABLE'
   AND l.stock_lot_id % 7 = 0;

-- ---------------------------------------------------------------- 확인
SELECT (SELECT COUNT(*) FROM stock_lot l
          JOIN product_option o ON o.product_option_id = l.product_option_id
          JOIN product p ON p.product_id = o.product_id
         WHERE p.product_code LIKE 'DUMMY-%')                                    AS lots_total,
       (SELECT COUNT(*) FROM stock_lot l
          JOIN product_option o ON o.product_option_id = l.product_option_id
          JOIN product p ON p.product_id = o.product_id
         WHERE p.product_code LIKE 'DUMMY-%'
           AND l.status = 'AVAILABLE'
           AND l.expiry_date BETWEEN CURDATE() + INTERVAL 10 DAY
                                 AND CURDATE() + INTERVAL 13 DAY
           AND l.available_qty >= 30)                                            AS batch_candidates,
       (SELECT COUNT(*) FROM stock_lot l
          JOIN product_option o ON o.product_option_id = l.product_option_id
          JOIN product p ON p.product_id = o.product_id
         WHERE p.product_code LIKE 'DUMMY-%' AND l.status = 'EXPIRED')           AS expired_piled_up,
       (SELECT COUNT(*) FROM stock_movement m
          JOIN stock_lot l ON l.stock_lot_id = m.stock_lot_id
          JOIN product_option o ON o.product_option_id = l.product_option_id
          JOIN product p ON p.product_id = o.product_id
         WHERE p.product_code LIKE 'DUMMY-%')                                    AS disposals;
