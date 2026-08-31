package com.freshmarket.coupon.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 쿠폰 한 장의 온디맨드 정합성 검증 결과. CouponConsistencyScheduler 의 새벽 배치와 서비스
// 메서드를 공유하되 대상을 이 쿠폰 하나로 좁힌 것이다
public record AdminCouponConsistencyCheckResponse(
        @Schema(description = "쿠폰이 스스로 기억하는 발급 수", example = "10000") long issuedQuantityOnCoupon,
        @Schema(description = "실제 발급 행 수", example = "10000") long actualIssueCount,
        @Schema(description = "같은 쿠폰을 둘 이상 받은 회원 수", example = "0") long duplicatedMembers,
        @Schema(description = "비어 있는 선착순 순번들") List<Integer> seqGaps,
        @Schema(description = "위 셋이 모두 어긋나지 않았는지", example = "true") boolean consistent
) {
}
