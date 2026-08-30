package com.freshmarket.coupon.domain.dto;

import java.util.List;

public record MemberCouponHistoryResponse(List<MemberCouponHistoryEntry> history) {
}
