package com.freshmarket.coupon.domain.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.freshmarket.common.exception.CommonErrorCode;
import com.freshmarket.coupon.domain.cache.CachedCoupon;
import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.dto.CouponIssueResponse;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.exception.DataAccessFailures;
import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import com.freshmarket.coupon.domain.issue.CouponIssueQueue;
import com.freshmarket.coupon.domain.issue.CouponWriteCircuit;
import com.freshmarket.coupon.domain.issue.IssueOutcome;
import com.freshmarket.coupon.domain.issue.IssueTicket;
import com.freshmarket.coupon.domain.redis.CouponSeqAllocator;
import com.freshmarket.coupon.domain.redis.CouponSeqUnavailableException;
import com.freshmarket.coupon.domain.redis.SeqOutcome;
import com.freshmarket.member.MemberApi;
import com.freshmarket.member.MemberInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 선착순 발급의 네 단계를 잇는다. 자격을 보고, 순번을 받고, 큐에 넣고, 결과를 응답으로 바꾼다.
 *
 * <p>쿠폰은 {@link CouponCache} 에서 받는다. 발급 창 안에서는 그 값들이 얼어붙으므로 요청마다
 * DB 를 칠 이유가 없다({@code docs/coupon/coupon.md} 3장).
 *
 * <p>이 클래스에는 트랜잭션이 없다. 쓰기는 플러시 스레드가 자기 커넥션으로 하고, 이 클래스는
 * 큐에 넣고 기다릴 뿐이다. 여기서 트랜잭션을 열면 기다리는 내내 커넥션을 쥐어, 5장의 커넥션 예산을 요청 수만큼
 * 먹는다({@code docs/coupon/coupon.md} 5장).
 */
@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponCache couponCache;
    private final MemberApi memberApi;
    private final CouponSeqAllocator allocator;
    private final CouponIssueQueue queue;
    private final CouponWriteCircuit writeCircuit;
    private final CouponIssueProperties properties;
    private final Clock clock;

    /**
     * 이 회원에게 쿠폰 한 장을 발급한다.
     *
     * @return 순번과, 그것이 이번에 받은 것인지 원래 갖고 있던 것인지
     * @throws CouponException 소진(최종)이거나 혼잡(다시 시도할 값이 있다)일 때
     */
    public CouponIssueResponse issue(long couponId, long memberId) {
        CachedCoupon coupon = findCoupon(couponId);
        verifyIssuable(coupon, memberId);

        /*
         * 쓸 수 없으면 번호도 받지 않는다.
         * DB 가 죽어도 Redis 는 멀쩡해 순번 확보 회로는 안 열린다. 이 확인이 없으면 요청마다
         * 번호를 태우고 요청 예산을 다 기다린 뒤에야 실패한다.
         */
        if (!writeCircuit.acceptsWrites()) {
            throw new CouponException(CouponErrorCode.CONGESTED);
        }

        /*
         * 이 메서드가 자리를 순번보다 먼저 본다.
         * 요청 스레드가 순번을 받고 나서 큐에 못 넣으면 그 번호를 반납해야 하는데, 순서를 뒤집으면 그 경로가
         * 아예 안 생긴다.
         */
        if (!queue.hasRoom()) {
            throw new CouponException(CouponErrorCode.CONGESTED);
        }

        return switch (allocateSeq(couponId, memberId, coupon.totalQuantity())) {
            case SeqOutcome.Allocated allocated -> record(coupon, memberId, allocated.seq());
            case SeqOutcome.AlreadyIssued issued -> CouponIssueResponse.alreadyIssued(issued.seq());
            case SeqOutcome.SoldOut ignored -> throw new CouponException(CouponErrorCode.SOLD_OUT);
            // 준비 전이거나 재건 중이다. 재고는 있을 수 있으므로 최종이 아니다
            case SeqOutcome.NotPrepared ignored -> throw new CouponException(CouponErrorCode.CONGESTED);
        };
    }

    /*
     * 캐시가 비어 있으면 이 호출이 DB 까지 간다. 이벤트가 도는 동안에는 대개 캐시가 답한다.
     */
    private CachedCoupon findCoupon(long couponId) {
        try {
            return couponCache.find(couponId)
                    .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        } catch (DataAccessException e) {
            throw congestedIfTransient(e);
        }
    }

    /*
     * Redis 가 답하지 않거나 회로가 열려 있으면 순번을 못 받는다.
     * 재고는 남아 있을 수 있으므로 소진이 아니고, 대체 순번 발급기를 두지 않기로 했으므로
     * 이 메서드가 여기서 끊는다. 이미 큐에 들어간 요청은 이 경로와 무관하게 그대로 발급된다.
     */
    private SeqOutcome allocateSeq(long couponId, long memberId, int issueLimit) {
        try {
            return allocator.allocate(couponId, memberId, issueLimit);
        } catch (CouponSeqUnavailableException e) {
            throw new CouponException(CouponErrorCode.CONGESTED, e);
        }
    }

    private void verifyIssuable(CachedCoupon coupon, long memberId) {
        if (!coupon.isLimited()) {
            throw new CouponException(CouponErrorCode.NOT_LIMITED);
        }
        if (!coupon.active() || !coupon.isIssuableAt(LocalDateTime.now(clock))) {
            throw new CouponException(CouponErrorCode.NOT_ISSUABLE);
        }
        verifyTargetGrade(coupon, memberId);
    }

    /*
     * 대상 등급이 걸려 있지 않으면 이 메서드가 회원을 아예 읽지 않는다.
     * 그 읽기는 DB 왕복이라 선착순 경로에서 되도록 피한다. 대부분의 선착순 쿠폰은 등급을 안 건다.
     */
    private void verifyTargetGrade(CachedCoupon coupon, long memberId) {
        if (coupon.targetGradeId() == null) {
            return;
        }
        /*
         * memberId 는 검증된 토큰에서 온다. 그런데도 회원이 없다면 발급 이후에 탈퇴한 것이라,
         * 쿠폰이 아니라 자격 증명 쪽의 실패다.
         */
        MemberInfo member;
        try {
            member = memberApi.findMember(memberId)
                    .orElseThrow(() -> new CouponException(CommonErrorCode.UNAUTHENTICATED));
        } catch (DataAccessException e) {
            throw congestedIfTransient(e);
        }
        if (!coupon.isTargetGrade(member.memberGradeId())) {
            throw new CouponException(CouponErrorCode.NOT_TARGET_GRADE);
        }
    }

    /*
     * 이 메서드는 잠시 뒤면 될 실패만 혼잡으로 바꾼다.
     * SQL 문법 오류처럼 고쳐야 할 것까지 덮으면 그 버그가 재시도에 묻혀 안 드러난다.
     */
    private RuntimeException congestedIfTransient(DataAccessException e) {
        if (DataAccessFailures.isTransient(e)) {
            return new CouponException(CouponErrorCode.CONGESTED, e);
        }
        return e;
    }

    private CouponIssueResponse record(CachedCoupon coupon, long memberId, int issueSeq) {
        IssueTicket ticket = IssueTicket.of(
                coupon.couponId(), memberId, coupon.scope(), coupon.totalQuantity(), issueSeq);
        queue.submit(ticket);
        return waitFor(ticket);
    }

    private CouponIssueResponse waitFor(IssueTicket ticket) {
        try {
            IssueOutcome outcome = ticket.future()
                    .get(properties.requestBudget().toMillis(), TimeUnit.MILLISECONDS);
            return switch (outcome) {
                case IssueOutcome.Issued issued -> CouponIssueResponse.issued(issued.seq());
                case IssueOutcome.AlreadyIssued already -> CouponIssueResponse.alreadyIssued(already.seq());
                case IssueOutcome.Congested ignored -> throw new CouponException(CouponErrorCode.CONGESTED);
                /*
                 * 다시 시도해도 같을 실패다.
                 * 혼잡으로 답하면 그 버그가 재시도에 묻히므로 서버 오류로 드러나게 둔다.
                 */
                case IssueOutcome.Failed ignored -> throw new IllegalStateException("발급 기록이 실패했다");
            };
        } catch (TimeoutException e) {
            /*
             * 예산 안에 못 끝냈다. 이 스레드는 떠나지만 그 항목은 큐에 남아 결국 써진다.
             * 사용자가 다시 오면 매핑이 같은 번호를 돌려주므로 번호가 새로 타지 않는다.
             */
            throw new CouponException(CouponErrorCode.CONGESTED, e);
        } catch (ExecutionException e) {
            throw new CouponException(CouponErrorCode.CONGESTED, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CouponException(CouponErrorCode.CONGESTED, e);
        }
    }
}
