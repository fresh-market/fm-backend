-- (REL-2-07) 재시도 한도(MAX_RETRY_ATTEMPTS)에 도달한 행도 지금까지는 계속 스케줄러에
-- 다시 걸려 무한히 재시도됐다. 한도 도달 시 이 값을 TRUE로 두고 재시도 대상에서 뺀다 —
-- 사람이 보고 수동 개입할 수 있게 행 자체는 지우지 않는다(격리만 한다).
ALTER TABLE option_availability_sync_failure
    ADD COLUMN exhausted BOOLEAN NOT NULL DEFAULT FALSE;
