-- 부하 시험용 가상 회원을 만든다.
--
-- Flyway 마이그레이션이 아니다. 운영에서 돌면 안 되는 데이터라 손으로 실행한다.
--
--   docker exec -i freshmarket-mysql mysql --default-character-set=utf8mb4 \
--     -ufreshmarket -pfreshmarket freshmarket < loadtest/seed-members.sql
--
-- 문자셋을 안 주면 마지막 확인 SELECT 의 한글 별칭에서 구문 오류가 난다.
--
-- 요구사항이 "재고 10,000장에 20,000명 동시 요청" 이라 2만 명을 만든다.

-- 재귀 CTE 의 기본 깊이 상한이 1000 이다. 그대로 두면 1000명에서 끊긴다
SET SESSION cte_max_recursion_depth = 100000;

/*
 * member_id 를 직접 준다.
 * 토큰을 찍는 쪽이 어느 id 로 만들지 알아야 하고, AUTO_INCREMENT 에 맡기면 실행할 때마다 달라진다.
 * 100만 이상을 써서 손으로 만든 회원과 안 겹치게 한다.
 */
SET @id_base = 1000000;
SET @count = 20000;

-- 기본 등급을 쓴다. V4__seed_member_grades.sql 이 넣은 행이다
SET @grade = (SELECT member_grade_id FROM member_grade WHERE is_default = TRUE LIMIT 1);

/*
 * 다시 돌릴 수 있어야 한다.
 * uk_member_active_provider 가 provider_user_id 에 걸려 있어 두 번째 실행이 그냥 실패한다.
 * 발급분을 먼저 지우는 것은 fk_mc_member 때문이다.
 */
DELETE mc FROM member_coupon mc
 JOIN member m ON m.member_id = mc.member_id
 WHERE m.provider_user_id LIKE 'loadtest-%';
DELETE FROM member WHERE provider_user_id LIKE 'loadtest-%';

INSERT INTO member (member_id, provider, provider_user_id, member_grade_id, status, created_at, updated_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @count
)
SELECT @id_base + n,
       'KAKAO',
       CONCAT('loadtest-', n),
       @grade,
       'ACTIVE',
       NOW(6),
       NOW(6)
  FROM seq;

SELECT COUNT(*) AS 만든_회원,
       MIN(member_id) AS 첫_id,
       MAX(member_id) AS 끝_id
  FROM member
 WHERE provider_user_id LIKE 'loadtest-%';
