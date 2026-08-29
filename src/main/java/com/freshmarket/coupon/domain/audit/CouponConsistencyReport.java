package com.freshmarket.coupon.domain.audit;

import java.util.List;

/**
 * 검증 배치가 한 회차를 돌고 낸 결과다.
 *
 * <p><b>재는 것만 담고 고치지 않는다.</b> 요구사항이 같은 데이터로 재실행하면 같은 결과를
 * 요구하는데, 검증이 고치면 두 번째 실행의 결과가 첫 번째와 달라진다
 * ({@code docs/coupon/coupon.md} 10장).
 *
 * @param stock                   재고 세 값이 어긋난 쿠폰
 * @param seqGaps                 순번에 구멍이 있는 쿠폰
 * @param duplicates              한 회원이 둘 이상 받은 쿠폰
 * @param statusHistoryMismatches 마지막 전이가 현재 상태와 다른 발급분 수
 * @param issuesWithoutHistory    이력이 한 줄도 없는 발급분 수
 */
public record CouponConsistencyReport(List<CouponIssueCount> stock,
                                      List<CouponSeqSpan> seqGaps,
                                      List<DuplicateIssue> duplicates,
                                      long statusHistoryMismatches,
                                      long issuesWithoutHistory) {

    /*
     * 이 판정은 이력 없는 발급분을 어긋남에서 뺀다.
     * 발급 경로가 이력을 안 남기는 것이 지금의 설계라, 그것을 어긋남으로 세면 정상적으로 발급된
     * 행이 전부 걸린다. 그래서 값은 재서 리포트에 남기되 깨끗한지 여부에는 넣지 않는다.
     */
    public boolean clean() {
        return stock.isEmpty() && seqGaps.isEmpty() && duplicates.isEmpty() && statusHistoryMismatches == 0;
    }
}
