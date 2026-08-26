-- 재시도 횟수는 최초 실패 기록 시 1부터 시작하며, DB 레벨에서도 음수를 포함한 잘못된 값을 막는다.
ALTER TABLE refresh_token_revoke_failure
    ADD CONSTRAINT chk_refresh_token_revoke_failure_attempt_count
        CHECK (attempt_count >= 1);
