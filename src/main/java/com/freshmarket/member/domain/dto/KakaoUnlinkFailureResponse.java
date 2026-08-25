package com.freshmarket.member.domain.dto;

import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import java.time.LocalDateTime;

/** 관리자용 카카오 unlink 포기 건 조회 응답. */
public record KakaoUnlinkFailureResponse(
        Long failureId,
        Long memberId,
        int attemptCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static KakaoUnlinkFailureResponse from(KakaoUnlinkFailure failure) {
        return new KakaoUnlinkFailureResponse(
                failure.getId(),
                failure.getMemberId(),
                failure.getAttemptCount(),
                failure.getCreatedAt(),
                failure.getUpdatedAt());
    }
}
