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
 * (2026-08-20, DI-6-02) 탈퇴 시 카카오 unlink 1회 시도(KakaoUnlinkEventListener)가 실패하면 이
 * 행으로 남는다 — KakaoUnlinkRetryScheduler가 주기적으로 재시도하고, 성공하면 행을 지운다.
 *
 * (2026-08-27) attemptCount는 "원 시도"가 아니라 "재시도" 횟수만 센다 — MAX_RETRY_ATTEMPTS(5)는
 * 원 시도 1회를 뺀 순수 재시도 상한이라, 포기 시점까지 실제 카카오 호출은 원 시도 1 + 재시도 5 =
 * 총 6회다. (예전엔 리스너가 즉시 3회 재시도한 뒤에야 이 행을 만들면서도 attemptCount를 1부터
 * 시작해서, "MAX_RETRY_ATTEMPTS=5"라는 이름과 달리 실제 총 호출은 3+4=7회였다 — 이름과 동작이
 * 어긋나 있었다.)
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

    public static final int MAX_RETRY_ATTEMPTS = 5;

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

    /**
     * (2026-08-27, PR 리뷰 P1) 카카오가 4xx(429 제외)로 "정상적으로" 거절한 첫 시도를 기록할 때
     * 쓰는 진입점 — record()와 달리 attemptCount를 처음부터 MAX_RETRY_ATTEMPTS로 만들어서
     * retryAllPending()의 대상 조회(findByAttemptCountLessThanAndResolvedFalse)에서 바로
     * 빠지게 한다. 재시도해도 결과가 같은 실패를 자동 재시도 큐에 태울 이유가 없다.
     */
    public static KakaoUnlinkFailure recordRejected(Long memberId, String kakaoUserId) {
        KakaoUnlinkFailure failure = new KakaoUnlinkFailure(memberId, kakaoUserId);
        failure.markRejected();
        return failure;
    }

    public void markRetryFailed() {
        this.attemptCount++;
    }

    /** 이미 있던 행이 이후 재시도에서 다시 4xx 거절을 받았을 때 즉시 포기 상태로 전환한다. */
    public void markRejected() {
        this.attemptCount = MAX_RETRY_ATTEMPTS;
    }

    public boolean shouldGiveUp() {
        return attemptCount >= MAX_RETRY_ATTEMPTS;
    }

    /** 운영자가 포기 건을 확인하고 필요한 수동 조치를 마쳤음을 표시한다. */
    public void resolve() {
        this.resolved = true;
    }
}
