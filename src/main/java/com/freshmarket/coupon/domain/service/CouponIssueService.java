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
import com.freshmarket.coupon.domain.CouponIssueMetrics;
import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import com.freshmarket.coupon.domain.issue.CouponIssueQueue;
import com.freshmarket.coupon.domain.issue.CouponWriteCircuit;
import com.freshmarket.coupon.domain.issue.IssueOutcome;
import com.freshmarket.coupon.domain.issue.IssueResult;
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
 * 요청 스레드가 선착순 발급의 네 단계를 여기서 지난다. 자격을 보고, 순번을 받고, 큐에 넣고,
 * 플러시 스레드가 준 결과를 응답으로 바꾼다.
 *
 * <p>이 클래스는 쿠폰을 {@link CouponCache} 에서 받는다. 발급 창이 열려 있는 동안에는 쿠폰의
 * 판정 값들이 바뀌지 않으므로, 요청마다 DB 를 다시 읽을 이유가 없다
 * ({@code docs/coupon/coupon.md} 3장).
 *
 * <p><b>이 클래스는 트랜잭션을 열지 않는다.</b> DB 에 쓰는 것은 플러시 스레드이고 그 스레드가
 * 자기 커넥션을 쓴다. 요청 스레드는 큐에 넣고 결과를 기다릴 뿐이다. 여기서 트랜잭션을 열면
 * 요청 스레드가 기다리는 내내 커넥션을 쥐고 있어, 동시 요청 수만큼 커넥션이 필요해진다
 * ({@code docs/coupon/coupon.md} 5장의 커넥션 예산).
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
    private final CouponIssueMetrics metrics;
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
         * DB 회로가 열려 있으면 이 메서드는 순번을 받지 않고 여기서 끊는다.
         * DB 가 죽어도 Redis 는 멀쩡해서 순번 확보 회로는 안 열리기 때문에, 이 확인이 없으면
         * 요청마다 번호를 태우고 요청 예산을 다 기다린 뒤에야 실패한다.
         */
        if (!writeCircuit.acceptsWrites()) {
            throw congested(IssueResult.WRITE_CIRCUIT);
        }

        /*
         * 이 메서드는 큐에 자리가 있는지를 순번을 받기 전에 확인한다.
         * 순번을 먼저 받고 나서 큐에 못 넣으면 그 번호를 Redis 에 반납해야 하는데, 순서를 이렇게
         * 두면 반납할 일 자체가 안 생긴다.
         */
        if (!queue.hasRoom()) {
            throw congested(IssueResult.QUEUE_FULL);
        }

        return switch (allocateSeq(couponId, memberId, coupon.totalQuantity())) {
            case SeqOutcome.Allocated allocated -> record(coupon, memberId, allocated.seq());
            case SeqOutcome.AlreadyIssued issued -> alreadyIssued(issued.seq());
            case SeqOutcome.SoldOut ignored -> {
                metrics.record(IssueResult.SOLD_OUT);
                throw new CouponException(CouponErrorCode.SOLD_OUT);
            }
            /*
             * Redis 에 카운터가 없다. 관리자가 아직 이벤트를 안 열었거나 앱이 키를 재건하는 중이다.
             * 재고는 남아 있을 수 있으므로 소진이 아니고, 사용자가 다시 시도할 값이 있다.
             */
            case SeqOutcome.NotPrepared ignored -> throw congested(IssueResult.NOT_PREPARED);
        };
    }

    // 캐시가 비어 있을 때만 이 호출이 DB 까지 간다. 이벤트가 도는 동안에는 대개 캐시가 답한다
    private CachedCoupon findCoupon(long couponId) {
        try {
            return couponCache.find(couponId)
                    .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        } catch (DataAccessException e) {
            throw congestedIfTransient(e);
        }
    }

    /*
     * Redis 가 답하지 않거나 순번 확보 회로가 열려 있으면 이 메서드는 번호를 못 받는다.
     * 재고는 남아 있을 수 있으므로 소진이 아니고, 순번을 대신 내줄 곳을 두지 않기로 했으므로
     * 이 메서드가 요청을 여기서 끊는다.
     * 이미 큐에 들어간 요청은 이 경로와 무관하게 플러시 스레드가 그대로 발급한다.
     */
    private SeqOutcome allocateSeq(long couponId, long memberId, int issueLimit) {
        try {
            return allocator.allocate(couponId, memberId, issueLimit);
        } catch (CouponSeqUnavailableException e) {
            throw congested(IssueResult.SEQ_UNAVAILABLE, e);
        }
    }

    private void verifyIssuable(CachedCoupon coupon, long memberId) {
        if (!coupon.isLimited()) {
            metrics.record(IssueResult.NOT_ISSUABLE);
            throw new CouponException(CouponErrorCode.NOT_LIMITED);
        }
        if (!coupon.active() || !coupon.isIssuableAt(LocalDateTime.now(clock))) {
            metrics.record(IssueResult.NOT_ISSUABLE);
            throw new CouponException(CouponErrorCode.NOT_ISSUABLE);
        }
        verifyTargetGrade(coupon, memberId);
    }

    /*
     * 쿠폰에 대상 등급이 걸려 있지 않으면 이 메서드는 회원을 아예 읽지 않는다.
     * 회원을 읽는 것은 DB 왕복이라 선착순 경로에서 되도록 피한다. 대부분의 선착순 쿠폰은
     * 등급을 안 걸기 때문에 이 생략이 거의 모든 요청에 적용된다.
     */
    private void verifyTargetGrade(CachedCoupon coupon, long memberId) {
        if (coupon.targetGradeId() == null) {
            return;
        }
        /*
         * 이 memberId 는 앱이 검증한 토큰에서 꺼낸 값이다.
         * 그런데도 회원이 없다면 토큰을 발급한 뒤에 그 회원이 탈퇴한 것이므로, 쿠폰의 실패가
         * 아니라 자격 증명의 실패다. 그래서 쿠폰 오류 코드로 답하지 않는다.
         */
        MemberInfo member;
        try {
            member = memberApi.findMember(memberId)
                    .orElseThrow(() -> new CouponException(CommonErrorCode.UNAUTHENTICATED));
        } catch (DataAccessException e) {
            throw congestedIfTransient(e);
        }
        if (!coupon.isTargetGrade(member.memberGradeId())) {
            metrics.record(IssueResult.NOT_ISSUABLE);
            throw new CouponException(CouponErrorCode.NOT_TARGET_GRADE);
        }
    }

    /*
     * 이 메서드는 잠시 뒤에 다시 하면 될 실패만 혼잡으로 바꾼다.
     * SQL 문법 오류처럼 사람이 고쳐야 하는 것까지 혼잡으로 덮으면, 사용자의 재시도에 그 버그가
     * 묻혀서 아무도 모르게 된다.
     */
    private RuntimeException congestedIfTransient(DataAccessException e) {
        if (DataAccessFailures.isTransient(e)) {
            return congested(IssueResult.READ_FAILED, e);
        }
        return e;
    }

    /*
     * 이 메서드는 지표에 한 건 세고 나서 예외를 만들어 돌려준다. 던지는 것은 호출부가 한다.
     * 호출부가 throw congested(...) 로 읽혀야 그 자리에서 흐름이 끝난다는 것이 눈에 보인다.
     */
    private CouponException congested(IssueResult reason) {
        metrics.record(reason);
        return new CouponException(CouponErrorCode.CONGESTED);
    }

    private CouponException congested(IssueResult reason, Throwable cause) {
        metrics.record(reason);
        return new CouponException(CouponErrorCode.CONGESTED, cause);
    }

    private CouponIssueResponse alreadyIssued(int seq) {
        metrics.record(IssueResult.ALREADY_ISSUED);
        return CouponIssueResponse.alreadyIssued(seq);
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
                    .get(properties.commitWait().toMillis(), TimeUnit.MILLISECONDS);
            return switch (outcome) {
                case IssueOutcome.Issued issued -> {
                    metrics.record(IssueResult.ISSUED);
                    yield CouponIssueResponse.issued(issued.seq());
                }
                case IssueOutcome.AlreadyIssued already -> alreadyIssued(already.seq());
                // 플러시 스레드가 들고 온 사유를 그대로 센다. 여기서 뭉치면 순번 충돌과 DB 실패가 한 덩어리가 된다
                case IssueOutcome.Congested congested -> throw congested(congested.reason());
                /*
                 * 플러시 스레드가 고쳐야 할 실패를 들고 왔다. 사용자가 다시 시도해도 결과가 같다.
                 * 혼잡으로 답하면 그 버그가 재시도에 묻히므로, 서버 오류로 드러나게 둔다.
                 */
                case IssueOutcome.Failed ignored -> throw new IllegalStateException("발급 기록이 실패했다");
            };
        } catch (TimeoutException e) {
            /*
             * 플러시 스레드가 요청 예산 안에 답을 못 줬다.
             *
             * 여기서 떠나는 것은 기다리던 요청 스레드뿐이고, 그 티켓은 큐에 남아 결국 써진다.
             * 그래서 사용자는 실패로 들었는데 실제로는 발급된 상태가 될 수 있다. 사용자가 다시
             * 오면 Redis 매핑이 같은 번호를 돌려주므로 번호가 새로 타지는 않는다.
             */
            throw congested(IssueResult.BUDGET_EXCEEDED, e);
        } catch (ExecutionException e) {
            throw congested(IssueResult.ABORTED, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw congested(IssueResult.ABORTED, e);
        }
    }
}
