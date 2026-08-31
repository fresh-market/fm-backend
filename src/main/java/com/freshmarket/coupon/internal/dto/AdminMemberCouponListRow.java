package com.freshmarket.coupon.internal.dto;

import com.freshmarket.coupon.internal.entity.MemberCouponStatus;
import com.querydsl.core.annotations.QueryProjection;
import java.time.LocalDateTime;

// 관리자 발급 이력 목록 쿼리 전용 프로젝션. issuedAt 은 응답에도 실리고 다음 페이지 토큰 계산에도 쓴다
public record AdminMemberCouponListRow(
        Long memberCouponId,
        Long memberId,
        Integer issueSeq,
        MemberCouponStatus status,
        LocalDateTime issuedAt,
        LocalDateTime usedAt
) {

    @QueryProjection
    public AdminMemberCouponListRow {
        // QueryDSL이 @QueryProjection 생성자를 찾을 수 있게 record의 canonical 생성자에 어노테이션만 붙인다
    }
}
