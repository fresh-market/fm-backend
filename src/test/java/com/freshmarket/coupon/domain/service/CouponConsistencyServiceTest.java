package com.freshmarket.coupon.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import com.freshmarket.coupon.domain.audit.CouponConsistencyReport;
import com.freshmarket.coupon.domain.audit.CouponIssueCount;
import com.freshmarket.coupon.domain.audit.CouponSeqSpan;
import com.freshmarket.coupon.domain.audit.DuplicateIssue;
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
}
