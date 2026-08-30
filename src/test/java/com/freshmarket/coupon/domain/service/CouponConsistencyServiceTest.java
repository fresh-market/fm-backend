package com.freshmarket.coupon.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.freshmarket.coupon.domain.audit.CouponConsistencyReport;
import com.freshmarket.coupon.domain.audit.CouponIssueCount;
import com.freshmarket.coupon.domain.audit.CouponSeqSpan;
import com.freshmarket.coupon.domain.audit.DuplicateIssue;
import com.freshmarket.coupon.domain.dto.AdminCouponConsistencyCheckResponse;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.repository.CouponConsistencyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 서비스가 하는 일은 읽어 온 값에서 어긋난 것만 골라 리포트로 묶는 것이다.
 * 쿼리가 맞는지는 DB 가 있어야 알 수 있어 통합 시험이 따로 본다.
 */
@ExtendWith(MockitoExtension.class)
class CouponConsistencyServiceTest {

    @Mock
    private CouponConsistencyRepository couponConsistencyRepository;

    @InjectMocks
    private CouponConsistencyService sut;

    @Test
    void 어긋난_것이_없으면_깨끗하다고_답한다() {
        // given
        when(couponConsistencyRepository.findIssueCounts())
                .thenReturn(List.of(new CouponIssueCount(1L, 1000, 1000, 1000)));
        when(couponConsistencyRepository.findSeqSpans())
                .thenReturn(List.of(new CouponSeqSpan(1L, 1000, 1000)));
        when(couponConsistencyRepository.findDuplicateIssues()).thenReturn(List.of());

        // when
        CouponConsistencyReport report = sut.verify();

        // then
        assertThat(report.clean()).isTrue();
        assertThat(report.stock()).isEmpty();
        assertThat(report.seqGaps()).isEmpty();
    }

    // 멀쩡한 쿠폰까지 리포트에 담으면 어느 것이 문제인지 읽을 수 없다
    @Test
    void 어긋난_쿠폰만_리포트에_담는다() {
        // given
        CouponIssueCount 어긋난_쿠폰 = new CouponIssueCount(2L, 900, 1000, 997);
        when(couponConsistencyRepository.findIssueCounts())
                .thenReturn(List.of(new CouponIssueCount(1L, 1000, 1000, 1000), 어긋난_쿠폰));
        CouponSeqSpan 구멍난_쿠폰 = new CouponSeqSpan(2L, 1000, 997);
        when(couponConsistencyRepository.findSeqSpans())
                .thenReturn(List.of(new CouponSeqSpan(1L, 1000, 1000), 구멍난_쿠폰));
        when(couponConsistencyRepository.findDuplicateIssues())
                .thenReturn(List.of(new DuplicateIssue(3L, 77L, 2)));
        when(couponConsistencyRepository.countStatusHistoryMismatches()).thenReturn(5L);
        when(couponConsistencyRepository.countIssuesWithoutHistory()).thenReturn(11L);

        // when
        CouponConsistencyReport report = sut.verify();

        // then
        assertThat(report.clean()).isFalse();
        assertThat(report.stock()).containsExactly(어긋난_쿠폰);
        assertThat(report.seqGaps()).containsExactly(구멍난_쿠폰);
        assertThat(report.duplicates()).hasSize(1);
        assertThat(report.statusHistoryMismatches()).isEqualTo(5);
        assertThat(report.issuesWithoutHistory()).isEqualTo(11);
    }

    @Test
    void 쿠폰_하나만_검증할_때_그_쿠폰이_없으면_예외를_던진다() {
        // given
        when(couponConsistencyRepository.findIssueCount(999L)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> sut.verify(999L))
                .isInstanceOf(CouponException.class)
                .extracting(e -> ((CouponException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }

    // 개수(actual)와 최댓값(maxSeq)이 같으면 구멍이 없다는 뜻이라 findIssueSeqs까지는 안 부른다
    @Test
    void 쿠폰_하나만_검증할_때_전부_어긋나지_않았으면_일치한다() {
        // given
        when(couponConsistencyRepository.findIssueCount(1L))
                .thenReturn(Optional.of(new CouponIssueCount(1L, 1000, 1000, 1000)));
        when(couponConsistencyRepository.findMaxIssueSeq(1L)).thenReturn(Optional.of(1000));
        when(couponConsistencyRepository.countDuplicateMembers(1L)).thenReturn(0L);

        // when
        AdminCouponConsistencyCheckResponse response = sut.verify(1L);

        // then
        assertThat(response.issuedQuantityOnCoupon()).isEqualTo(1000);
        assertThat(response.actualIssueCount()).isEqualTo(1000);
        assertThat(response.duplicatedMembers()).isZero();
        assertThat(response.seqGaps()).isEmpty();
        assertThat(response.consistent()).isTrue();
    }

    // 순번 없이 나간 무제한 쿠폰. findMaxIssueSeq가 비어 있으니 구멍을 물을 대상이 아니다
    @Test
    void 쿠폰_하나만_검증할_때_무제한_쿠폰은_순번_구멍이_없다() {
        // given
        when(couponConsistencyRepository.findIssueCount(2L))
                .thenReturn(Optional.of(new CouponIssueCount(2L, 50, null, 50)));
        when(couponConsistencyRepository.findMaxIssueSeq(2L)).thenReturn(Optional.empty());
        when(couponConsistencyRepository.countDuplicateMembers(2L)).thenReturn(0L);

        // when
        AdminCouponConsistencyCheckResponse response = sut.verify(2L);

        // then
        assertThat(response.seqGaps()).isEmpty();
        assertThat(response.consistent()).isTrue();
    }

    @Test
    void 쿠폰_하나만_검증할_때_카운터가_어긋나면_불일치로_답한다() {
        // given
        when(couponConsistencyRepository.findIssueCount(3L))
                .thenReturn(Optional.of(new CouponIssueCount(3L, 900, 1000, 997)));
        when(couponConsistencyRepository.findMaxIssueSeq(3L)).thenReturn(Optional.of(997));
        when(couponConsistencyRepository.countDuplicateMembers(3L)).thenReturn(0L);

        // when
        AdminCouponConsistencyCheckResponse response = sut.verify(3L);

        // then
        assertThat(response.consistent()).isFalse();
    }

    // actual(3)이 maxSeq(4)와 달라야 findIssueSeqs로 전체를 읽고, seq가 [1,2,4]면 3이 빈 자리로 잡힌다
    @Test
    void 쿠폰_하나만_검증할_때_순번에_구멍이_있으면_그_번호를_찾는다() {
        // given
        when(couponConsistencyRepository.findIssueCount(4L))
                .thenReturn(Optional.of(new CouponIssueCount(4L, 4, 10, 3)));
        when(couponConsistencyRepository.findMaxIssueSeq(4L)).thenReturn(Optional.of(4));
        when(couponConsistencyRepository.findIssueSeqs(4L)).thenReturn(List.of(1, 2, 4));
        when(couponConsistencyRepository.countDuplicateMembers(4L)).thenReturn(0L);

        // when
        AdminCouponConsistencyCheckResponse response = sut.verify(4L);

        // then
        assertThat(response.seqGaps()).containsExactly(3);
        assertThat(response.consistent()).isFalse();
    }

    @Test
    void 쿠폰_하나만_검증할_때_중복_발급이_있으면_불일치로_답한다() {
        // given
        when(couponConsistencyRepository.findIssueCount(5L))
                .thenReturn(Optional.of(new CouponIssueCount(5L, 3, 10, 3)));
        when(couponConsistencyRepository.findMaxIssueSeq(5L)).thenReturn(Optional.of(3));
        when(couponConsistencyRepository.countDuplicateMembers(5L)).thenReturn(1L);

        // when
        AdminCouponConsistencyCheckResponse response = sut.verify(5L);

        // then
        assertThat(response.duplicatedMembers()).isEqualTo(1);
        assertThat(response.consistent()).isFalse();
    }
}
