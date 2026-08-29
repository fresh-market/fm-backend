-- 정합성 검증용 더미데이터를 만든다.
--
-- Flyway 마이그레이션이 아니다. 운영에서 돌면 안 되는 데이터라 손으로 실행한다.
--
--   docker exec -i freshmarket-mysql mysql -ufreshmarket -pfreshmarket freshmarket < loadtest/seed-dummy-data.sql
--
-- 요구사항이 "가상 유저 100만 명과 발급 이력 300만 건" 이다.
-- 부하 시험용 회원(loadtest-%)과는 쓰임이 달라 파일을 나눴다. 저쪽은 동시성 시연이고
-- 이쪽은 300만 건 전체를 훑는 검증의 대상이다.

/*
 * 손으로 만든 회원과도, 부하 시험용 회원(1000001~1020000)과도 안 겹치게 200만대를 쓴다.
 * id 를 직접 주는 이유는 발급 이력이 이 값을 참조해야 하기 때문이다.
 */
SET @member_base = 2000000;
SET @scale = 1000000;

-- ---------------------------------------------------------------- 다시 돌릴 수 있게 지운다
DELETE h FROM member_coupon_status_history h
  JOIN member_coupon mc ON mc.member_coupon_id = h.member_coupon_id
  JOIN coupon c ON c.coupon_id = mc.coupon_id
 WHERE c.name LIKE '더미%';
DELETE mc FROM member_coupon mc
  JOIN coupon c ON c.coupon_id = mc.coupon_id
 WHERE c.name LIKE '더미%';
DELETE FROM coupon WHERE name LIKE '더미%';
DELETE FROM member WHERE provider_user_id LIKE 'dummy-%';

-- ---------------------------------------------------------------- 1부터 @scale 까지의 수
/*
 * 재귀 CTE 로 100만까지 내려가면 느리다. 자리수 표를 교차 조인하는 쪽이 훨씬 빠르다.
 *
 * digits 는 임시 표로 두면 안 된다. MySQL 이 한 쿼리에서 같은 임시 표를 두 번 열지 못해
 * 여섯 번 자기 조인하는 아래 문장이 "Can't reopen table" 로 실패한다. 다 쓰고 지운다.
 */
DROP TABLE IF EXISTS _seed_digits;
CREATE TABLE _seed_digits (d INT);
INSERT INTO _seed_digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

DROP TEMPORARY TABLE IF EXISTS nums;
CREATE TEMPORARY TABLE nums (n INT PRIMARY KEY);
INSERT INTO nums (n)
SELECT a.d + b.d*10 + c.d*100 + e.d*1000 + f.d*10000 + g.d*100000 + 1
  FROM _seed_digits a, _seed_digits b, _seed_digits c,
       _seed_digits e, _seed_digits f, _seed_digits g;

DROP TABLE _seed_digits;

-- ---------------------------------------------------------------- 가상 유저 100만
INSERT INTO member (member_id, provider, provider_user_id, member_grade_id, status, created_at, updated_at)
SELECT @member_base + n,
       'KAKAO',
       CONCAT('dummy-', n),
       (SELECT member_grade_id FROM member_grade WHERE is_default = TRUE LIMIT 1),
       'ACTIVE',
       NOW(6),
       NOW(6)
  FROM nums;

-- ---------------------------------------------------------------- 쿠폰 셋
/*
 * 300만 건을 채우려면 쿠폰이 최소 셋 필요하다.
 * uk_mc_coupon_member 가 쿠폰당 1인 1매라, 회원 100만으로 한 쿠폰에서 낼 수 있는 것이 100만 건이다.
 *
 * 한정 둘과 무제한 하나로 나눈다. 한정 쪽이 10장의 "순번의 연속성" 검증 대상이고,
 * 무제한 쪽은 issue_seq 가 NULL 이라 그 검증에서 빠지는 것이 맞는지도 함께 드러난다.
 *
 * 혜택은 요구사항의 기획을 따른다. 상품 금액에서 30% 차감이라 ITEM 범위의 정률이다.
 */
INSERT INTO coupon (name, scope, discount_type, discount_value, min_order_amount,
                    total_quantity, issued_quantity, valid_from, valid_to, is_active,
                    created_at, updated_at)
VALUES ('더미 한정 A', 'ITEM', 'RATE', 30, 0, @scale, 0, '2026-01-01', '2030-12-31', FALSE, NOW(6), NOW(6)),
       ('더미 한정 B', 'ITEM', 'RATE', 30, 0, @scale, 0, '2026-01-01', '2030-12-31', FALSE, NOW(6), NOW(6)),
       ('더미 무제한', 'ITEM', 'RATE', 30, 0, NULL,   0, '2026-01-01', '2030-12-31', FALSE, NOW(6), NOW(6));

SET @c1 = (SELECT coupon_id FROM coupon WHERE name = '더미 한정 A');
SET @c2 = (SELECT coupon_id FROM coupon WHERE name = '더미 한정 B');
SET @c3 = (SELECT coupon_id FROM coupon WHERE name = '더미 무제한');

-- ---------------------------------------------------------------- 발급 이력 300만
/*
 * 상태를 네 가지로 흩는다. 요구사항이 발급, 사용, 취소, 만료를 전부 관리하라고 한다.
 * chk_mc_used_at 이 status = 'USED' 와 used_at 을 묶으므로 그 둘만 짝이 맞으면 된다.
 */
INSERT INTO member_coupon (coupon_id, member_id, scope, issue_limit, issue_seq,
                           status, issued_at, used_at, created_at, updated_at)
SELECT @c1, @member_base + n, 'ITEM', @scale, n,
       CASE WHEN n % 10 < 6 THEN 'ISSUED'
            WHEN n % 10 < 8 THEN 'USED'
            WHEN n % 10 = 8 THEN 'EXPIRED'
            ELSE 'CANCELED' END,
       NOW(6),
       CASE WHEN n % 10 BETWEEN 6 AND 7 THEN NOW(6) ELSE NULL END,
       NOW(6), NOW(6)
  FROM nums;

INSERT INTO member_coupon (coupon_id, member_id, scope, issue_limit, issue_seq,
                           status, issued_at, used_at, created_at, updated_at)
SELECT @c2, @member_base + n, 'ITEM', @scale, n,
       CASE WHEN n % 10 < 6 THEN 'ISSUED'
            WHEN n % 10 < 8 THEN 'USED'
            WHEN n % 10 = 8 THEN 'EXPIRED'
            ELSE 'CANCELED' END,
       NOW(6),
       CASE WHEN n % 10 BETWEEN 6 AND 7 THEN NOW(6) ELSE NULL END,
       NOW(6), NOW(6)
  FROM nums;

-- 무제한 쿠폰이라 issue_limit 과 issue_seq 가 둘 다 NULL 이다 (chk_mc_issue_seq)
INSERT INTO member_coupon (coupon_id, member_id, scope, issue_limit, issue_seq,
                           status, issued_at, used_at, created_at, updated_at)
SELECT @c3, @member_base + n, 'ITEM', NULL, NULL,
       CASE WHEN n % 10 < 6 THEN 'ISSUED'
            WHEN n % 10 < 8 THEN 'USED'
            WHEN n % 10 = 8 THEN 'EXPIRED'
            ELSE 'CANCELED' END,
       NOW(6),
       CASE WHEN n % 10 BETWEEN 6 AND 7 THEN NOW(6) ELSE NULL END,
       NOW(6), NOW(6)
  FROM nums;

-- ---------------------------------------------------------------- 발급 카운터
/*
 * issued_quantity 를 실제 행 수에 맞춘다.
 * 10장의 첫 항목이 이 값과 실제 행 수를 대조한다. 0 으로 두면 더미데이터 자체가 검증에서
 * 어긋난 것으로 잡혀, 검증이 무엇을 재는 데이터인지 알 수 없게 된다.
 *
 * 운영에서는 이 값을 발급 중에 아무도 안 올리고 이벤트 정리 배치가 나중에 맞춘다.
 * 여기서는 그 배치가 이미 돈 뒤의 상태를 만든다.
 */
UPDATE coupon c
   SET issued_quantity = (SELECT COUNT(*) FROM member_coupon WHERE coupon_id = c.coupon_id)
 WHERE c.coupon_id IN (@c1, @c2, @c3);

-- ---------------------------------------------------------------- 상태 이력
/*
 * 상태만 흩어 두고 이력을 안 넣으면 10장의 "상태와 이력의 마지막 전이가 같은가" 가
 * 300만 건 전부에서 어긋난다. 더미데이터 자체가 검증을 통과하지 못하는 데이터가 된다.
 *
 * 최초 발급은 from_status 가 NULL 이다 (chk_mcsh_from_status).
 */
INSERT INTO member_coupon_status_history (member_coupon_id, from_status, to_status, reason, created_at)
SELECT mc.member_coupon_id, NULL, 'ISSUED', '더미 최초 발급', mc.issued_at
  FROM member_coupon mc
 WHERE mc.coupon_id IN (@c1, @c2, @c3);

-- 발급 뒤에 상태가 바뀐 것만 전이를 하나 더 남긴다 (chk_mcsh_transition 이 같은 상태를 막는다)
INSERT INTO member_coupon_status_history (member_coupon_id, from_status, to_status, reason, created_at)
SELECT mc.member_coupon_id, 'ISSUED', mc.status,
       CASE mc.status WHEN 'USED' THEN '더미 사용'
                      WHEN 'EXPIRED' THEN '더미 유효기간 도래'
                      ELSE '더미 주문 취소' END,
       mc.updated_at
  FROM member_coupon mc
 WHERE mc.coupon_id IN (@c1, @c2, @c3)
   AND mc.status <> 'ISSUED';

-- ---------------------------------------------------------------- 확인
SELECT (SELECT COUNT(*) FROM member WHERE provider_user_id LIKE 'dummy-%')            AS 가상_유저,
       (SELECT COUNT(*) FROM member_coupon WHERE coupon_id IN (@c1, @c2, @c3))        AS 발급_이력,
       (SELECT COUNT(*) FROM member_coupon_status_history h
          JOIN member_coupon mc ON mc.member_coupon_id = h.member_coupon_id
         WHERE mc.coupon_id IN (@c1, @c2, @c3))                                       AS 상태_이력;
