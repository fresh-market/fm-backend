-- retryAllPending()의 조회(OptionAvailabilitySyncFailureRepository.findByIdGreaterThanAndAttemptCountLessThanOrderByIdAsc)
-- 는 id 범위로 훑으면서 attempt_count로 걸러낸다. 인덱스가 없으면 포기한(exhausted) 행이 쌓일수록
-- 10분마다 그 행들까지 매번 다시 읽게 된다.
CREATE INDEX idx_option_availability_sync_failure_pending
    ON option_availability_sync_failure (attempt_count, option_availability_sync_failure_id);
