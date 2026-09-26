package com.freshmarket.coupon.internal.service;

import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.issue.IssueTicket;
import com.freshmarket.coupon.internal.repository.CouponV1SeqRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponBulkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * v1 브랜치 전용. 순번 확보와 발급 기록을 한 트랜잭션으로 묶는다.
 *
 * 둘이 한 트랜잭션이어야 하는 이유는 조건부 UPDATE 가 잡은 락이다. 그 락을 커밋까지 쥐고 있어야
 * 남이 같은 번호를 못 받는다. 나누면 UPDATE 를 커밋한 뒤 INSERT 가 실패할 때 그 번호가 아무도
 * 쓰지 않는 결번이 되고, 재고가 줄어든 채 발급은 안 된 상태가 남는다.
 *
 * v2 부터는 이 묶음이 없다. Redis 가 번호를 주고 그 번호를 쥔 상태를 pending 키가 표현하므로,
 * 쓰기가 실패해도 기준 시간이 지나면 번호가 다시 나온다 (docs/coupon/coupon.md 3장).
 *
 * 이 클래스는 부하 시험 비교용이고 병합 대상이 아니다.
 */
@Service
@RequiredArgsConstructor
public class CouponV1IssueTransaction {

    private final CouponV1SeqRepository seqRepository;
    private final MemberCouponBulkRepository bulkRepository;

    /**
     * 순번을 확보하고 그 번호로 한 건을 기록한다.
     *
     * @return 확보한 순번. 재고가 없으면 0
     */
    @Transactional
    public int issue(long couponId, long memberId, CouponScope scope, int issueLimit) {
        int seq = seqRepository.claim(couponId);
        if (seq == 0) {
            return 0;
        }
        bulkRepository.insertOne(IssueTicket.of(couponId, memberId, scope, issueLimit, seq));
        return seq;
    }
}
