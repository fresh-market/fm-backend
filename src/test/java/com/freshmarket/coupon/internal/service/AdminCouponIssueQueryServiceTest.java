package com.freshmarket.coupon.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.coupon.internal.dto.AdminMemberCouponListItem;
import com.freshmarket.coupon.internal.dto.AdminMemberCouponListRow;
import com.freshmarket.coupon.internal.dto.AdminMemberCouponSearchCondition;
import com.freshmarket.coupon.internal.dto.AdminMemberCouponHistoryEntry;
import com.freshmarket.coupon.internal.dto.AdminMemberCouponHistoryResponse;
import com.freshmarket.coupon.internal.entity.MemberCouponStatus;
import com.freshmarket.coupon.internal.exception.CouponErrorCode;
import com.freshmarket.coupon.internal.exception.CouponException;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponHistoryRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponQueryRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 쿼리가 맞는지는 DB가 있어야 알 수 있어 통합 시험이 따로 본다. 여기서는 존재 확인, 페이지
 * 자르기, 다음 페이지 토큰 계산처럼 서비스가 스스로 하는 일만 본다.
 */
@ExtendWith(MockitoExtension.class)
class AdminCouponIssueQueryServiceTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private MemberCouponQueryRepository memberCouponQueryRepository;

    @Mock
    private MemberCouponRepository memberCouponRepository;

    @Mock
    private MemberCouponHistoryRepository memberCouponHistoryRepository;

    @InjectMocks
    private AdminCouponIssueQueryService sut;

    @Test
    void 발급_이력_목록을_조회할_때_그_쿠폰이_없으면_예외를_던진다() {
        // given
        when(couponRepository.existsById(999L)).thenReturn(false);
        AdminMemberCouponSearchCondition condition =
                new AdminMemberCouponSearchCondition(999L, null, null, 20);

        // when, then
        assertThatThrownBy(() -> sut.findIssues(condition))
                .isInstanceOf(CouponException.class)
                .extracting(e -> ((CouponException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    void 발급_이력_목록이_페이지_크기를_넘으면_다음_페이지_토큰을_만든다() {
        // given
        when(couponRepository.existsById(1L)).thenReturn(true);
        AdminMemberCouponSearchCondition condition = new AdminMemberCouponSearchCondition(1L, null, null, 1);
        LocalDateTime now = LocalDateTime.now();
        when(memberCouponQueryRepository.searchIssues(condition)).thenReturn(List.of(
                new AdminMemberCouponListRow(2L, 20L, 2, MemberCouponStatus.ISSUED, now, null),
                new AdminMemberCouponListRow(1L, 10L, 1, MemberCouponStatus.ISSUED, now.minusMinutes(1), null)));

        // when
        CursorPageResponse<AdminMemberCouponListItem> result = sut.findIssues(condition);

        // then
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).memberCouponId()).isEqualTo(2L);
        assertThat(result.nextPageToken()).isNotNull();
    }

    @Test
    void 발급_이력_목록이_페이지_크기_안이면_다음_페이지_토큰이_없다() {
        // given
        when(couponRepository.existsById(1L)).thenReturn(true);
        AdminMemberCouponSearchCondition condition = new AdminMemberCouponSearchCondition(1L, null, null, 20);
        when(memberCouponQueryRepository.searchIssues(any())).thenReturn(List.of(
                new AdminMemberCouponListRow(1L, 10L, 1, MemberCouponStatus.ISSUED, LocalDateTime.now(), null)));

        // when
        CursorPageResponse<AdminMemberCouponListItem> result = sut.findIssues(condition);

        // then
        assertThat(result.items()).hasSize(1);
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void 발급분_상태_이력을_조회할_때_그_발급분이_없으면_예외를_던진다() {
        // given
        when(memberCouponRepository.existsById(999L)).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> sut.findHistory(999L))
                .isInstanceOf(CouponException.class)
                .extracting(e -> ((CouponException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.MEMBER_COUPON_NOT_FOUND);
    }

    @Test
    void 발급분_상태_이력을_리포지토리가_준_순서_그대로_돌려준다() {
        // given
        when(memberCouponRepository.existsById(1L)).thenReturn(true);
        List<AdminMemberCouponHistoryEntry> history = List.of(
                new AdminMemberCouponHistoryEntry(null, "ISSUED", null, null, LocalDateTime.now()),
                new AdminMemberCouponHistoryEntry("ISSUED", "USED", null, null, LocalDateTime.now()));
        when(memberCouponHistoryRepository.findByMemberCouponId(1L)).thenReturn(history);

        // when
        AdminMemberCouponHistoryResponse response = sut.findHistory(1L);

        // then
        assertThat(response.history()).isEqualTo(history);
    }
}
