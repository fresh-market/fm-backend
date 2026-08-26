-- 관리자 로그아웃 실패 재처리를 여러 batch 인스턴스가 동시에 수행해도 같은 외부 Redis 정리를
-- 중복 실행하지 않도록 선점 상태를 추가한다. processing_started_at은 중간 종료된 선점을
-- 일정 시간 뒤 재획득하기 위한 lease 기준 시각이다.
ALTER TABLE admin_logout_failure
    ADD COLUMN processing BOOLEAN NOT NULL DEFAULT FALSE AFTER resolved,
    ADD COLUMN processing_started_at DATETIME(6) NULL AFTER processing,
    ADD KEY idx_admin_logout_failure_pending (resolved, admin_logout_failure_id),
    ADD CONSTRAINT chk_admin_logout_failure_attempt_count CHECK (attempt_count >= 1);