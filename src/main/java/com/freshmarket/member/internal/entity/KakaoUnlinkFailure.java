package com.freshmarket.member.internal.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 애플리케이션 탈퇴 뒤 카카오 unlink가 실패하면 이 행으로 남는다. 매일 03시 배치가 재시도하고,
 * 성공한 경우에만 회원 상태를 WITHDRAWN으로 확정한 뒤 행을 지운다.
 *
 * 감사 이력이 아니라 "아직 처리 안 된 것"만 담는 큐라, 처리 끝난 행을 남겨두지 않는다.
 *
 * PK 컬럼명은 스키마 전체 컨벤션(schema-design-rationale.md)대로 kakao_unlink_failure_id다 —
 * BaseMutableTimeEntity의 id 필드는 컬럼명을 "id"로 매핑하므로, @AttributeOverride로 실제 DDL의
 * PK 컬럼명에 맞춰준다.
 */
@Entity
@Getter
@Table(name = "kakao_unlink_failure")
@AttributeOverride(name = "id", column = @Column(name = "kakao_unlink_failure_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KakaoUnlinkFailure extends BaseMutableTimeEntity {

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(name = "kakao_user_id", nullable = false, length = 100)
    private String kakaoUserId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    private KakaoUnlinkFailure(Long memberId, String kakaoUserId) {
        this.memberId = memberId;
        this.kakaoUserId = kakaoUserId;
        this.attemptCount = 0; // 원 시도 1회는 이미 실패해서 여기 왔다 — 재시도는 아직 0번
    }

    /** 원 시도가 실패한 뒤 처음 기록할 때 쓰는 유일한 생성 진입점. */
    public static KakaoUnlinkFailure record(Long memberId, String kakaoUserId) {
        return new KakaoUnlinkFailure(memberId, kakaoUserId);
    }

    public void markRetryFailed() {
        this.attemptCount++;
    }

}
