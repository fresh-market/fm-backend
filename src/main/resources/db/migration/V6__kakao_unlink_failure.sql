-- (2026-08-20, DI-6-02) 탈퇴 시 카카오 연동 해제가 즉시 재시도(3회) 후에도 실패하면, 우리 DB만
-- WITHDRAWN이고 카카오는 연결이 살아있는 상태로 영구히 어긋날 수 있다. 이 표가 그 실패를
-- 남겨두는 아웃박스 큐다 — KakaoUnlinkRetryScheduler가 주기적으로 재시도하고, 성공하면 행을
-- 지운다(감사 이력이 아니라 "아직 처리 안 된 것"만 추적하는 큐라서, 처리 끝난 행을 안 남긴다).
CREATE TABLE kakao_unlink_failure (
    kakao_unlink_failure_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    kakao_user_id VARCHAR(100) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_kakao_unlink_failure_member UNIQUE (member_id),
    -- (DI-3-03) 이 저장소의 다른 테이블은 전부 참조무결성을 FK로 강제하는데 이 표만 빠져 있었다.
    -- member_id가 member 테이블에 없는 값을 가리킬 수 있으면, 스케줄러가 존재하지 않는 회원을
    -- 재시도하거나(고아 행) member가 먼저 삭제됐을 때 정합성이 깨진다.
    CONSTRAINT fk_kakao_unlink_failure_member FOREIGN KEY (member_id) REFERENCES member (member_id)
);
