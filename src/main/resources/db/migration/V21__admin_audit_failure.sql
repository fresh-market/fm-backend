-- 관리자 로그아웃 성공 감사 로그 저장이 실패했을 때 유실하지 않고 재처리하기 위한 아웃박스.
CREATE TABLE admin_audit_failure (
    admin_audit_failure_id BIGINT       NOT NULL AUTO_INCREMENT,
    admin_id               BIGINT       NOT NULL,
    action                 VARCHAR(50)  NOT NULL,
    target                 VARCHAR(100) NULL,
    detail                 TEXT         NULL,
    attempt_count          INT          NOT NULL DEFAULT 1,
    resolved               BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    PRIMARY KEY (admin_audit_failure_id),
    KEY idx_admin_audit_failure_pending (resolved, admin_audit_failure_id),
    CONSTRAINT chk_admin_audit_failure_attempt_count CHECK (attempt_count >= 1),
    CONSTRAINT fk_admin_audit_failure_admin FOREIGN KEY (admin_id) REFERENCES admin (admin_id)
);
