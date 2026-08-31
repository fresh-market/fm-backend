package com.freshmarket.coupon.domain.service;

import java.util.List;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.coupon.domain.dto.AdminCouponListItem;
import com.freshmarket.coupon.domain.dto.AdminCouponListRow;
import com.freshmarket.coupon.domain.dto.AdminCouponSearchCondition;
import com.freshmarket.coupon.domain.repository.CouponQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자가 쿠폰 정의를 관리하는 서비스. 목록 조회부터 시작하고, 생성/활성화 등은 이어서 붙인다
@Service
@RequiredArgsConstructor
public class AdminCouponService {

    private final CouponQueryRepository couponQueryRepository;

    /*
     * 조건에 맞는 쿠폰 목록을 커서 기반으로 조회한다(API-3-04, API-5-01).
     * 리포지토리가 pageSize + 1건을 주므로 초과분을 잘라내고 다음 페이지 여부를 판단한다.
     */
    @Transactional(readOnly = true)
    public CursorPageResponse<AdminCouponListItem> findAll(AdminCouponSearchCondition condition) {
        List<AdminCouponListRow> found = couponQueryRepository.search(condition);

        boolean hasNext = found.size() > condition.pageSize();
        List<AdminCouponListRow> page = hasNext ? found.subList(0, condition.pageSize()) : found;

        List<AdminCouponListItem> items = page.stream().map(AdminCouponService::toItem).toList();
        return CursorPageResponse.of(items, nextTokenOf(page, hasNext));
    }

    private static AdminCouponListItem toItem(AdminCouponListRow row) {
        return new AdminCouponListItem(
                row.couponId(), row.name(), row.scope(), row.discountType(), row.discountValue(),
                row.maxDiscountAmount(), row.minOrderAmount(), row.totalQuantity(), row.issuedQuantity(),
                row.issueStartAt(), row.issueEndAt(), row.validFrom(), row.validTo(), row.targetGradeId(),
                row.active());
    }

    // 다음 페이지 토큰. 마지막 행의 생성일과 id로 커서를 만든다(정렬이 createdAt desc, id desc 고정)
    private static String nextTokenOf(List<AdminCouponListRow> page, boolean hasNext) {
        if (!hasNext || page.isEmpty()) {
            return null;
        }
        AdminCouponListRow last = page.get(page.size() - 1);
        return PageTokens.encode(new PageCursor(last.couponId(), last.createdAt().toString()));
    }
}
