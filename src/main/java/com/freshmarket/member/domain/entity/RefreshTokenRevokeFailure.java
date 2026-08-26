package com.freshmarket.member.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * (2026-08-25) MemberTokenService.revoke()가 DB 백업(member.refresh_token_hash) 정리나 Redis
 * 정리(기본 레코드/activeKey) 중 하나라도 실패하면 이 행으로 남는다 —
 * RefreshTokenRevokeRetryScheduler가 주기적으로 재시도하고, 둘 다 성공하면 행을 지운다. 감사
 * 이력이 아니라 "아직 처리 안 된 것"만 담는 큐라, 처리 끝난 행을 남겨두지 않는다
 * (KakaoUnlinkFailure와 같은 패턴).
 *
 * refreshTokenHash를 같이 들고 있는 이유: 재시도 시점엔 이 회원이 이미 재로그인해서 DB의
 * refreshTokenHash가 새 값으로 바뀌어 있을 수 있다. 재시도는 memberId만으로 무조건 지우지
 * 않고, 여기 저장된 해시와 지금 DB 값이 같을 때만 지우는 조건부 UPDATE
 * (MemberRepository.clearRefreshTokenIfMatches)를 써야 그 사이 재로그인한 새 세션을 잘못
 * 지우지 않는다 — 이미 다른 값으로 바뀌었다면(=이 실패 건은 이미 의미가 없어졌다면) 그
 * UPDATE는 조용히 0건으로 끝난다.
 *
 * PK 컬럼명은 스키마 전체 컨벤션(schema-design-rationale.md)대로
 * refresh_token_revoke_failure_id다 — BaseMutableTimeEntity의 id 필드는 컬럼명을 "id"로
 * 매핑하므로, @AttributeOverride로 실제 DDL의 PK 컬럼명에 맞춘다.
 */
@Entity
@Getter
@Table(name = "refresh_token_revoke_failure", uniqueConstraints =
        @UniqueConstraint(name = "uk_refresh_token_revoke_failure_member_hash",
                columnNames = {"member_id", "refresh_token_hash"}))
@AttributeOverride(name = "id", column = @Column(name = "refresh_token_revoke_failure_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenRevokeFailure extends BaseMutableTimeEntity {

    private static final int MAX_RETRY_ATTEMPTS = 5;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "role", nullable = false, length = 30)
    private String role;

    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    private String refreshTokenHash;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    private RefreshTokenRevokeFailure(Long memberId, String role, String refreshTokenHash) {
        this.memberId = memberId;
        this.role = role;
        this.refreshTokenHash = refreshTokenHash;
        this.attemptCount = 1;
    }

    /** revoke() 정리가 처음 실패했을 때만 쓰는 유일한 생성 진입점. */
    public static RefreshTokenRevokeFailure record(Long memberId, String role, String refreshTokenHash) {
        return new RefreshTokenRevokeFailure(memberId, role, refreshTokenHash);
    }

    public void markRetryFailed() {
        this.attemptCount++;
    }

    public boolean shouldGiveUp() {
        return attemptCount >= MAX_RETRY_ATTEMPTS;
    }
}
