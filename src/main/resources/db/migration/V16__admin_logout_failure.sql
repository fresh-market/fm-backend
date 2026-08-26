-- 관리자 로그아웃 시 Refresh Token 정리(Redis/DB)가 즉시 재시도(3회)까지 실패하면 이 표에 남는다.
-- AdminLogoutFailureScheduler가 10분 간격으로 미해결 건(resolved=FALSE)을 재시도한다.
-- kakao_unlink_failure와 달리 성공해도 행을 지우지 않는다 — 로그아웃 정리 실패는 보안 이력으로
-- 남겨두는 게 낫다는 판단(감사 목적)이라, resolved 플래그로만 완료 여부를 표시한다.
CREATE TABLE admin_logout_failure (
                                      admin_logout_failure_id BIGINT       NOT NULL AUTO_INCREMENT, -- admin_logout_failure PK
                                      admin_id                BIGINT       NOT NULL, -- 대상 관리자 FK
                                      refresh_token_hash      CHAR(64)     NULL, -- 실패 시점에 알고 있던 Refresh Token 해시(SHA-256). DB 폐기 자체가 실패해 해시를 못 구했으면 NULL
                                      redis_failed            BOOLEAN      NOT NULL DEFAULT FALSE, -- Redis 쪽 정리가 아직 안 끝났는지
                                      db_failed                BOOLEAN     NOT NULL DEFAULT FALSE, -- DB 쪽 폐기가 아직 안 끝났는지
                                      attempt_count            INT         NOT NULL DEFAULT 1, -- 즉시 재시도 이후 이 행이 재시도된 총 횟수(스케줄러 포함)
                                      resolved                 BOOLEAN     NOT NULL DEFAULT FALSE, -- Redis/DB 둘 다 정리가 끝났는지. 끝나도 행은 지우지 않는다(이력)
                                      created_at               DATETIME(6) NOT NULL, -- 생성 시각(애플리케이션에서 생성)
                                      updated_at               DATETIME(6) NOT NULL, -- 수정 시각(애플리케이션에서 생성)
                                      PRIMARY KEY (admin_logout_failure_id),
    -- 관리자당 미해결 실패는 하나만 추적한다. 같은 관리자가 다시 실패하면 새 행 대신 기존 행을 재오픈한다
                                      UNIQUE KEY uk_admin_logout_failure_admin (admin_id),
                                      CONSTRAINT fk_admin_logout_failure_admin FOREIGN KEY (admin_id) REFERENCES admin (admin_id)
); -- 관리자 로그아웃 Refresh Token 정리 실패 아웃박스
