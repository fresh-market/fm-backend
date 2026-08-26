-- 폐기 재시도는 회원이 아니라 토큰 해시 단위로 보존한다. 같은 회원이 재로그인한 뒤 또 폐기에
-- 실패해도, 먼저 실패한 토큰의 primary Redis 레코드를 정리할 작업을 덮어쓰지 않는다.
ALTER TABLE refresh_token_revoke_failure
    DROP INDEX uk_refresh_token_revoke_failure_member,
    ADD CONSTRAINT uk_refresh_token_revoke_failure_member_hash UNIQUE (member_id, refresh_token_hash);
