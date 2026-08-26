-- 같은 관리자가 재로그인한 뒤 서로 다른 Refresh Token 폐기에 연속으로 실패해도
-- 이전 토큰의 미해결 정리 작업이 새 토큰 실패에 덮어써지지 않도록 실패 행을 토큰 단위로 보존한다.
-- 동일 (admin_id, refresh_token_hash) 실패만 upsert 대상으로 합쳐지고, 서로 다른 해시는 별도 행이 된다.
-- MySQL UNIQUE는 NULL 값을 서로 같은 값으로 보지 않으므로 refresh_token_hash를 알 수 없는 장애 건은
-- 서로 덮어쓰지 않고 각각 보존된다. 토큰을 특정할 수 없는 상태에서 임의로 합치는 것보다 안전하다.
ALTER TABLE admin_logout_failure
    DROP INDEX uk_admin_logout_failure_admin,
    ADD CONSTRAINT uk_admin_logout_failure_admin_hash
        UNIQUE (admin_id, refresh_token_hash);
