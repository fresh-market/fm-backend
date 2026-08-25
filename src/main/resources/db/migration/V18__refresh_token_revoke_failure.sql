-- (2026-08-25) MemberTokenService.revoke() 시 DB 백업(member.refresh_token_hash) 정리나 Redis
-- 정리(기본 레코드/activeKey) 중 하나라도 실패하면, 무효화됐어야 할 refreshToken이 (a) Redis
-- 정리 실패면 Redis가 정상인 동안에도 여전히 유효하게 남아 있거나, (b) DB 백업 정리 실패면
-- 이후 Redis 완전장애 시 reissueViaDbFallback()이 여전히 그 토큰을 신뢰하는 문제가 생길 수
-- 있다. 이 표가 그 실패를 남겨두는 아웃박스 큐다 — RefreshTokenRevokeRetryScheduler가 주기적으로
-- 재시도하고, 둘 다 성공하면 행을 지운다(감사 이력이 아니라 "아직 처리 안 된 것"만 추적하는
-- 큐라서, 처리 끝난 행을 안 남긴다). kakao_unlink_failure(V6)와 같은 패턴.
--
-- refresh_token_hash를 member_id와 별도로 남기는 이유: 재시도 시점에 이 회원이 이미 재로그인해서
-- member.refresh_token_hash가 새 값으로 덮어써졌을 수 있다. member_id만으로 무조건 지우면 그
-- 새 세션까지 지워버리는 역설이 생기므로, 재시도는 반드시 "저장된 이 해시와 지금 DB 값이 같을
-- 때만 지운다"는 조건부 UPDATE(MemberRepository.clearRefreshTokenIfMatches)로 해야 안전하다 —
-- 이미 다른 값으로 바뀌었다면(=이 실패 건은 이미 의미가 없어졌다면) 그 UPDATE는 조용히 0건으로
-- 끝난다.
CREATE TABLE refresh_token_revoke_failure (
    refresh_token_revoke_failure_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    role VARCHAR(30) NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_refresh_token_revoke_failure_member UNIQUE (member_id),
    -- (DI-3-03과 같은 이유) member가 먼저 삭제되면 고아 행이 남거나 스케줄러가 존재하지 않는
    -- 회원을 재시도하게 된다.
    CONSTRAINT fk_refresh_token_revoke_failure_member FOREIGN KEY (member_id) REFERENCES member (member_id)
);
