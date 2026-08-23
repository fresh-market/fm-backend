package com.freshmarket.member.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * (2026-08-20, DI-6-02) 탈퇴 시 카카오 unlink가 즉시 재시도(KakaoUnlinkEventListener의 3회) 후에도
 * 실패하면 이 행으로 남는다 — KakaoUnlinkRetryScheduler가 주기적으로 재시도하고, 성공하면 행을
 * 지운다. 감사 이력이 아니라 "아직 처리 안 된 것"만 담는 큐라, 처리 끝난 행을 남겨두지 않는다.
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

    private static final int MAX_RETRY_ATTEMPTS = 5;

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(name = "kakao_user_id", nullable = false, length = 100)
    private String kakaoUserId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    private KakaoUnlinkFailure(Long memberId, String kakaoUserId) {
        this.memberId = memberId;
        this.kakaoUserId = kakaoUserId;
        this.attemptCount = 1;
    }

    /** 즉시 재시도가 다 실패한 뒤 처음 기록할 때 쓰는 유일한 생성 진입점. */
    public static KakaoUnlinkFailure record(Long memberId, String kakaoUserId) {
        return new KakaoUnlinkFailure(memberId, kakaoUserId);
    }

    public void markRetryFailed() {
        this.attemptCount++;
    }

    public boolean shouldGiveUp() {
        return attemptCount >= MAX_RETRY_ATTEMPTS;
    }
}
