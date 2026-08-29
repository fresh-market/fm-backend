-- 카카오 unlink가 실패해 재시도 대기 중인 회원도 애플리케이션 탈퇴는 끝난 상태다.
-- deleted_at은 WITHDRAWN과 마찬가지로 유지해 같은 카카오 계정의 재가입을 막는다.
ALTER TABLE member DROP CHECK chk_member_status;
ALTER TABLE member DROP CHECK chk_member_withdrawn;
ALTER TABLE member
    ADD CONSTRAINT chk_member_status
        CHECK (status IN ('PENDING_PROFILE', 'ACTIVE', 'BLOCKED', 'WITHDRAWN_FAILED', 'WITHDRAWN')),
    ADD CONSTRAINT chk_member_withdrawn
        CHECK ((status IN ('WITHDRAWN_FAILED', 'WITHDRAWN') AND deleted_at IS NOT NULL)
            OR (status NOT IN ('WITHDRAWN_FAILED', 'WITHDRAWN') AND deleted_at IS NULL));

-- 배포 전 아웃박스에 남아 있던 건도 새 상태 전이로 이어져야 한다. 그렇지 않으면 배치가
-- unlink에 성공해도 WITHDRAWN_FAILED -> WITHDRAWN 조건부 확정에 걸리지 않아 큐가 남는다.
UPDATE member m
JOIN kakao_unlink_failure f ON f.member_id = m.member_id
SET m.status = 'WITHDRAWN_FAILED'
WHERE f.resolved = FALSE
  AND m.status = 'WITHDRAWN';
