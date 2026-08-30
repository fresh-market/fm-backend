package com.freshmarket.coupon.domain.service;

import java.util.List;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.coupon.domain.dto.AdminMemberCouponListItem;
import com.freshmarket.coupon.domain.dto.AdminMemberCouponListRow;
import com.freshmarket.coupon.domain.dto.AdminMemberCouponSearchCondition;
import com.freshmarket.coupon.domain.dto.MemberCouponHistoryResponse;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.repository.CouponQueryRepository;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import com.freshmarket.coupon.domain.repository.MemberCouponHistoryRepository;
import com.freshmarket.coupon.domain.repository.MemberCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리자가 이미 나간 발급분을 조회하는 두 API를 담당한다. 쿠폰 하나의 발급 목록과 발급분 하나의
 * 상태 전이 이력이다. 둘 다 읽기 전용이고, 발급 경로({@link CouponIssueService})와 자원을
 * 다투지 않도록 별도 서비스로 둔다.
 */
@Service
@RequiredArgsConstructor
public class AdminCouponIssueQueryService {

    private final CouponRepository couponRepository;
    private final CouponQueryRepository couponQueryRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final MemberCouponHistoryRepository memberCouponHistoryRepository;

    /**
     * 이 쿠폰으로 나간 발급분을 상태로 걸러 커서 기반으로 조회한다.
     *
     * @throws CouponException 그 쿠폰이 없으면
     */
    public CursorPageResponse<AdminMemberCouponListItem> findIssues(AdminMemberCouponSearchCondition condition) {
        if (!couponRepository.existsById(condition.couponId())) {
            throw new CouponException(CouponErrorCode.COUPON_NOT_FOUND);
        }
        List<AdminMemberCouponListRow> found = couponQueryRepository.searchIssues(condition);

        boolean hasNext = found.size() > condition.pageSize();
        List<AdminMemberCouponListRow> page = hasNext ? found.subList(0, condition.pageSize()) : found;

        List<AdminMemberCouponListItem> items = page.stream().map(AdminCouponIssueQueryService::toItem).toList();
        return CursorPageResponse.of(items, nextTokenOf(page, hasNext));
    }

    private static AdminMemberCouponListItem toItem(AdminMemberCouponListRow row) {
        return new AdminMemberCouponListItem(
                row.memberCouponId(), row.memberId(), row.issueSeq(), row.status(), row.issuedAt(), row.usedAt());
    }

    // 다음 페이지 토큰. 마지막 행의 발급 시각과 id로 커서를 만든다(정렬이 issuedAt desc, id desc 고정)
    private static String nextTokenOf(List<AdminMemberCouponListRow> page, boolean hasNext) {
        if (!hasNext || page.isEmpty()) {
            return null;
        }
        AdminMemberCouponListRow last = page.get(page.size() - 1);
        return PageTokens.encode(new PageCursor(last.memberCouponId(), last.issuedAt().toString()));
    }

    /**
     * 이 발급분이 지금까지 거친 상태 전이를 순서대로 준다.
     *
     * @throws CouponException 그 발급분이 없으면
     */
    public MemberCouponHistoryResponse findHistory(long memberCouponId) {
        if (!memberCouponRepository.existsById(memberCouponId)) {
            throw new CouponException(CouponErrorCode.MEMBER_COUPON_NOT_FOUND);
        }
        return new MemberCouponHistoryResponse(memberCouponHistoryRepository.findByMemberCouponId(memberCouponId));
    }
}
