package com.freshmarket.coupon.domain.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.freshmarket.coupon.domain.audit.CouponConsistencyReport;
import com.freshmarket.coupon.domain.audit.CouponIssueCount;
import com.freshmarket.coupon.domain.audit.CouponSeqSpan;
import com.freshmarket.coupon.domain.audit.DuplicateIssue;
import com.freshmarket.coupon.domain.dto.CouponConsistencyCheckResponse;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.repository.CouponConsistencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이력과 재고가 어긋나지 않았음을 스스로 확인한다. 요구사항이 <b>검증 수단 자체를 구현물로</b>
 * 요구한 자리다({@code docs/coupon/requirement.md} 의 "쿠폰 정합성 검증").
 *
 * <p>{@code docs/coupon/coupon.md} 10장의 다섯 항목을 그대로 잰다.
 *
 * <pre>
 * 발급 수와 issued_quantity   카운터가 실제 행 수와 다른가
 * 발급 수와 total_quantity    한정 수량을 넘겼는가
 * 순번의 연속성                구멍 수가 곧 어긋남이다
 * 1인 1매                     UNIQUE 가 막지만 결과로도 확인한다
 * 상태와 이력 (R5)             마지막 전이가 현재 상태와 같은가
 * </pre>
 *
 * <p><b>상태를 들고 있지 않는다.</b> 매번 처음부터 전부 훑어 계산하고 중간 결과를 저장하지
 * 않는다. 저장해 두고 다음 회차에서 재사용하면 "같은 데이터로 재실행하면 같은 결과" 가 깨진다.
 *
 * <p><b>고치는 배치와 섞지 않는다.</b> 검증이 고치면 두 번째 실행이 첫 번째와 다른 결과를 낸다.
 * 고치는 일은 이벤트 종료 배치와 9장의 회수가 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponConsistencyService {

    private final CouponConsistencyRepository couponConsistencyRepository;

    /**
     * 한 회차를 돌고 결과를 낸다.
     *
     * <p>다섯 항목을 <b>한 트랜잭션 안에서</b> 읽는다. 나눠 읽으면 그 사이에 들어온 발급이
     * 앞 항목에는 없고 뒤 항목에는 있어, 아무도 안 틀렸는데 어긋남으로 잡힌다. 격리 수준이
     * {@code REPEATABLE READ} 라 한 트랜잭션 안의 여러 쿼리가 같은 스냅숏을 본다.
     *
     * <p>{@code readOnly} 는 여기서 <b>선언이자 방어</b>다. 이 경로가 무엇도 안 고친다는 것을
     * 드러내고, 실수로 쓰기가 섞이면 그때 막힌다.
     *
     * <p><b>대가는 그동안 InnoDB 가 undo 로그를 못 지우는 것이다.</b> InnoDB 는 열려 있는 리드
     * 뷰보다 새로운 undo 를 purge 하지 못하므로, 이 트랜잭션이 300만 행을 훑는 몇 분 내내
     * history list 가 자란다. 읽기라서 락은 안 잡지만 공짜는 아니다. {@code application-batch.yml}
     * 이 {@code socketTimeout} 을 300초로 늘려 둔 것이 이 회차가 분 단위라는 증거다.
     *
     * <p>그 대가를 감당하는 방법이 <b>새벽 4시 반</b>이라는 시각이다. 쓰기가 거의 없는 창이라
     * 자란 history list 가 곧 따라잡힌다. 이 배치를 주간으로 옮기거나 주기를 당기려는 사람은
     * 스냅숏 이득이 아니라 이 대가부터 다시 재야 한다.
     */
    @Transactional(readOnly = true)
    public CouponConsistencyReport verify() {
        List<CouponIssueCount> stock = couponConsistencyRepository.findIssueCounts().stream()
                .filter(CouponIssueCount::mismatched)
                .toList();
        List<CouponSeqSpan> seqGaps = couponConsistencyRepository.findSeqSpans().stream()
                .filter(CouponSeqSpan::hasGap)
                .toList();
        List<DuplicateIssue> duplicates = couponConsistencyRepository.findDuplicateIssues();

        CouponConsistencyReport report = new CouponConsistencyReport(stock, seqGaps, duplicates,
                couponConsistencyRepository.countStatusHistoryMismatches(),
                couponConsistencyRepository.countIssuesWithoutHistory());
        report(report);
        return report;
    }

    /**
     * 쿠폰 한 장만 관리자 요청에 맞춰 즉시 확인한다.
     *
     * <p>{@link #verify()} 의 새벽 배치와 서비스 메서드를 공유하지 않는다. 그 배치는 300만 건
     * 전체를 REPEATABLE READ 스냅숏 안에서 훑어야 하지만, 여기는 이 쿠폰의 발급 행만 보므로
     * 그 스냅숏이 필요 없다. 한정 수량 자체가 작아 각 쿼리가 짧게 끝난다.
     *
     * @throws com.freshmarket.coupon.domain.exception.CouponException 그 쿠폰이 없으면
     */
    public CouponConsistencyCheckResponse verify(long couponId) {
        CouponIssueCount counts = couponConsistencyRepository.findIssueCount(couponId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        List<Integer> seqGaps = findSeqGaps(couponId);
        long duplicatedMembers = couponConsistencyRepository.countDuplicateMembers(couponId);
        boolean consistent = !counts.counterMismatched() && duplicatedMembers == 0 && seqGaps.isEmpty();
        return new CouponConsistencyCheckResponse(
                counts.issuedQuantity(), counts.actual(), duplicatedMembers, seqGaps, consistent);
    }

    // 나간 순번들 사이에서 비어 있는 번호를 찾는다. 한정 수량만큼만 훑으므로 전체 배치와 비용이 다르다
    private List<Integer> findSeqGaps(long couponId) {
        List<Integer> seqs = couponConsistencyRepository.findIssueSeqs(couponId);
        if (seqs.isEmpty()) {
            return List.of();
        }
        Set<Integer> present = new HashSet<>(seqs);
        int maxSeq = seqs.get(seqs.size() - 1);
        List<Integer> gaps = new ArrayList<>();
        for (int seq = 1; seq <= maxSeq; seq++) {
            if (!present.contains(seq)) {
                gaps.add(seq);
            }
        }
        return gaps;
    }

    /*
     * 리포트를 로그로 남긴다.
     * 요약 한 줄은 회차마다 같은 자리에 나와 대시보드가 회차 사이를 비교할 수 있고,
     * 어긋난 것이 있으면 어느 쿠폰인지까지 줄을 나눠 남긴다.
     */
    private void report(CouponConsistencyReport report) {
        log.info("event=COUPON_CONSISTENCY_CHECKED clean={} stock={} seqGaps={} duplicates={}"
                        + " statusHistoryMismatches={} issuesWithoutHistory={}",
                report.clean(), report.stock().size(), report.seqGaps().size(), report.duplicates().size(),
                report.statusHistoryMismatches(), report.issuesWithoutHistory());
        report.stock().forEach(counted -> log.warn(
                "event=COUPON_STOCK_MISMATCH couponId={} issuedQuantity={} totalQuantity={} actual={} remaining={}",
                counted.couponId(), counted.issuedQuantity(), counted.totalQuantity(),
                counted.actual(), counted.remaining()));
        report.seqGaps().forEach(span -> log.warn(
                "event=COUPON_SEQ_GAP couponId={} maxSeq={} issued={} gap={}",
                span.couponId(), span.maxSeq(), span.issued(), span.gap()));
        report.duplicates().forEach(duplicate -> log.warn(
                "event=COUPON_DUPLICATE_ISSUE couponId={} memberId={} count={}",
                duplicate.couponId(), duplicate.memberId(), duplicate.count()));
    }
}
