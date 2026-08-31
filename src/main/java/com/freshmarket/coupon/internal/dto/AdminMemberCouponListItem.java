package com.freshmarket.coupon.internal.dto;

import com.freshmarket.coupon.internal.entity.MemberCouponStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

// 관리자 발급 이력 목록의 항목 하나
public record AdminMemberCouponListItem(
        @Schema(description = "발급분 ID", example = "77") Long memberCouponId,
        @Schema(description = "회원 ID", example = "42") Long memberId,
        @Schema(description = "선착순 순번. 무제한 쿠폰은 null", example = "3120") Integer issueSeq,
        @Schema(description = "발급분 상태") MemberCouponStatus status,
        @Schema(description = "발급 시각") LocalDateTime issuedAt,
        @Schema(description = "사용 시각. 미사용이면 null") LocalDateTime usedAt
) {
}
