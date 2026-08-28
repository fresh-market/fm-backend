package com.freshmarket.coupon.domain.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.freshmarket.coupon.domain.entity.MemberCouponStatus;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.repository.MemberCouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발급분의 상태를 옮긴다. 발급 이후의 전이(사용, 취소, 만료)가 전부 여기로 모인다.
 *
 * <p>요구사항이 <b>"동일한 상태 변경 요청이 반복해서, 또는 동시에 발생해도 한 번만 반영되어야
 * 한다"</b> 고 못 박은 것이 이 클래스가 지키는 것이다({@code docs/coupon/requirement.md}).
 *
 * <p>전이는 리포지터리의 조건부 갱신이 맡고, 이 클래스는 <b>갱신된 행 수가 0 일 때 그 사유를
 * 가르는 일</b>을 한다. 원인이 셋인데 결과가 같아서, 안 가르면 이미 사용한 쿠폰을 또 쓰려는 정당한
 * 재시도와 남의 쿠폰을 건드리는 요청이 같은 응답을 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberCouponStatusService {

    /*
     * 만료 배치가 한 번에 잡는 건수다.
     * 300만 건을 한 문장으로 바꾸면 그동안 락이 걸려 있어 사용 요청이 전부 막힌다.
     */
    private static final int EXPIRE_CHUNK = 1000;

    private final MemberCouponRepository memberCouponRepository;
    private final Clock clock;

    /**
     * 주문에서 쿠폰을 쓴다.
     *
     * <p>이미 사용된 것이면 조용히 끝난다. <b>그것은 실패가 아니라 늦게 도착한 같은 요청</b>이고,
     * 실패로 답하면 재시도한 호출자가 못 쓴 줄 알고 다시 시도한다.
     */
    @Transactional
    public void use(long memberCouponId, long memberId) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (memberCouponRepository.markUsed(memberCouponId, memberId, now) == 1) {
            memberCouponRepository.recordTransition(memberCouponId,
                    MemberCouponStatus.ISSUED.name(), MemberCouponStatus.USED.name(), "주문에서 사용", now);
            return;
        }
        verifyAlready(memberCouponId, memberId, MemberCouponStatus.USED);
    }

    /**
     * 주문이 취소되어 사용을 철회한다.
     *
     * <p>{@code used_at} 을 함께 비우는 것은 리포지터리가 한다. 언제 썼었는지는 이력의 사용 전이
     * 행이 갖는다.
     */
    @Transactional
    public void cancelUse(long memberCouponId, long memberId) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (memberCouponRepository.markCanceled(memberCouponId, memberId, now) == 1) {
            memberCouponRepository.recordTransition(memberCouponId,
                    MemberCouponStatus.USED.name(), MemberCouponStatus.CANCELED.name(), "주문 취소", now);
            return;
        }
        verifyAlready(memberCouponId, memberId, MemberCouponStatus.CANCELED);
    }

    /**
     * 0행의 사유를 가른다.
     *
     * <pre>
     * 행이 없다        남의 것이거나 없다.  둘을 가르지 않는다
     * 이미 그 상태다    늦게 도착한 같은 요청이다.  조용히 끝낸다
     * 그 밖의 상태다    지금 상태에서 할 수 없는 전이다
     * </pre>
     *
     * <p>0행일 때만 도는 경로라 정상 흐름에 읽기가 하나 더 붙지 않는다.
     */
    private void verifyAlready(long memberCouponId, long memberId, MemberCouponStatus target) {
        List<String> found = memberCouponRepository.findStatus(memberCouponId, memberId);
        if (found.isEmpty()) {
            throw new CouponException(CouponErrorCode.MEMBER_COUPON_NOT_FOUND);
        }
        if (!target.name().equals(found.get(0))) {
            throw new CouponException(CouponErrorCode.INVALID_STATUS_TRANSITION);
        }
        log.info("event=MEMBER_COUPON_ALREADY_IN_STATE memberCouponId={} status={}", memberCouponId, target);
    }

    /**
     * 유효기간이 지난 발급분을 청크 하나만큼 만료 처리한다. 배치가 0 이 나올 때까지 부른다.
     *
     * <p><b>반복을 이 클래스가 갖지 않는다.</b> 같은 클래스의 메서드를 부르면 프록시를 안 거쳐
     * 이 애너테이션이 안 걸리고, 그러면 상태 갱신과 이력이 서로 다른 트랜잭션으로 나뉜다.
     * 그 사이에 앱이 죽으면 상태는 바뀌었는데 이력이 없는 행이 남아 10장의 검증이 어긋난다.
     *
     * <p>청크로 끊는 것은 락 때문이다. 300만 건을 한 트랜잭션으로 묶으면 전부 끝날 때까지
     * 그 행들이 잠겨 사용 요청이 막히고, 중간에 실패하면 앞서 처리한 것까지 되돌아간다.
     *
     * @return 이번 청크가 만료시킨 건수. 0 이면 더 볼 것이 없다
     */
    @Transactional
    public int expireOverdueChunk() {
        LocalDate today = LocalDate.now(clock);
        List<Long> ids = memberCouponRepository.findExpirable(today, EXPIRE_CHUNK);
        if (ids.isEmpty()) {
            return 0;
        }

        /*
         * 고른 뒤 갱신하기까지 사이에 사용 요청이 끼어들 수 있다.
         * 그때는 사용 쪽이 이기고 이 갱신이 그 행을 건너뛴다. 고른 수와 바꾼 수가 다를 수 있어
         * 이력도 실제로 바뀐 것만 남아야 한다.
         */
        LocalDateTime now = LocalDateTime.now(clock);
        int expired = memberCouponRepository.markExpired(ids, today, now);
        if (expired > 0) {
            memberCouponRepository.recordExpiredTransitions(ids, "유효기간 도래", now);
            log.info("event=MEMBER_COUPON_EXPIRED count={}", expired);
        }
        return expired;
    }
}
