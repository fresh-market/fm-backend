package com.freshmarket.coupon.internal.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.freshmarket.coupon.internal.entity.MemberCouponStatus;
import com.freshmarket.coupon.internal.exception.CouponErrorCode;
import com.freshmarket.coupon.internal.exception.CouponException;
import com.freshmarket.coupon.internal.repository.MemberCouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문과 배치가 발급분의 상태를 옮기는 자리다. 발급 이후의 전이(사용, 취소, 만료)가 전부 여기로 모인다.
 *
 * <p>요구사항이 <b>"동일한 상태 변경 요청이 반복해서, 또는 동시에 발생해도 한 번만 반영되어야
 * 한다"</b> 고 못 박은 것이 이 클래스가 지키는 것이다({@code docs/coupon/requirement.md}).
 *
 * <p>전이는 리포지터리의 조건부 갱신이 맡고, 이 클래스는 <b>갱신된 행 수가 0 일 때 그 사유를
 * 가르는 일</b>을 한다. 원인이 여럿인데 결과가 같아서, 안 가르면 이미 사용한 쿠폰을 또 쓰려는 정당한
 * 재시도와 남의 쿠폰을 건드리는 요청이 같은 응답을 받는다.
 *
 * <p><b>만료는 층이 셋이다.</b> 셋이 하는 일이 서로 달라 하나로 합칠 수 없다.
 *
 * <pre>
 * 사용 조건    정확성.  기간이 지난 것은 표시가 어떻든 안 나간다
 * 조회 시 해소  표시.    저장된 값이 늦어도 회원에게는 만료로 보인다
 * 만료 배치    저장된 값을 맞춘다.  정합성 검증과 통계가 읽을 값이다
 * </pre>
 *
 * <p>가운데 층은 아직 없다. 보유 쿠폰 조회 API 를 만들 때 <b>{@code status} 를 그대로 뿌리면
 * 안 된다.</b> 기간 밖이면 만료로, 기간 안의 {@code CANCELED} 는 사용 가능으로 해소해야 한다
 * ({@code V1__init_schema.sql} 의 {@code member_coupon.status} 주석).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberCouponStatusService {

    /*
     * 만료 배치가 한 번에 잡는 건수다.
     * 300만 건을 한 문장으로 바꾸면 그 문장이 끝날 때까지 그 행들이 잠겨, 그 사이에 들어온
     * 사용 요청이 전부 막힌다.
     */
    private static final int EXPIRE_CHUNK = 1000;

    /*
     * 사용으로 갈 수 있는 출발 상태다.
     * CANCELED 가 여기 있는 것은 주문 취소로 돌려받은 쿠폰이 기간이 남았으면 다시 쓸 수 있어서다.
     * 한 번에 IN 으로 묶지 않고 하나씩 시도하는 것은 이력의 from_status 를 정확히 알기 위해서다.
     */
    private static final List<MemberCouponStatus> USABLE_FROM =
            List.of(MemberCouponStatus.ISSUED, MemberCouponStatus.CANCELED);

    private final MemberCouponRepository memberCouponRepository;
    private final Clock clock;

    /**
     * 주문이 이 발급분을 사용 처리한다.
     *
     * <p>이미 사용된 것이면 이 메서드는 조용히 끝난다. <b>그것은 실패가 아니라 늦게 도착한 같은
     * 요청</b>이고, 실패로 답하면 재시도한 호출자가 못 쓴 줄 알고 또 시도한다.
     *
     * <p>갱신을 출발 상태별로 나눠 시도한다. 정상 경로는 첫 번째({@code ISSUED})에서 끝나고,
     * 두 번째는 <b>주문 취소로 돌려받은 쿠폰을 다시 쓰는 경우</b>에만 돈다. 한 문장으로 묶으면
     * 무엇에서 출발했는지를 갱신 뒤에는 알 수 없어 이력의 {@code from_status} 를 못 채운다.
     */
    @Transactional
    public void use(long memberCouponId, long memberId) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        for (MemberCouponStatus from : USABLE_FROM) {
            if (memberCouponRepository.markUsed(memberCouponId, memberId, from.name(), today, now) == 1) {
                memberCouponRepository.recordTransition(memberCouponId,
                        from.name(), MemberCouponStatus.USED.name(), "주문에서 사용", now);
                return;
            }
        }
        verifyUsable(memberCouponId, memberId, today);
    }

    /**
     * 주문이 취소되어 이 발급분의 사용을 철회한다.
     *
     * <p>{@code used_at} 을 함께 비우는 일은 리포지터리의 갱신문이 한다. 언제 썼었는지는 이력의
     * 사용 전이 행이 들고 있으므로 그 값을 지워도 기록이 사라지지 않는다.
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
     * 사용이 0행으로 끝난 사유를 가른다.
     *
     * <pre>
     * 행이 없다        남의 것이거나 없다.  둘을 가르지 않는다
     * 이미 사용됐다     늦게 도착한 같은 요청이다.  조용히 끝낸다
     * 기간 밖이다      만료 표시가 아직 안 붙었어도 쓸 수 없다
     * 그 밖의 상태다    지금 상태에서 할 수 없는 전이다
     * </pre>
     *
     * <p>이미 사용된 것인지를 <b>기간보다 먼저</b> 본다. 순서를 뒤집으면 사용한 뒤 기간이 지난
     * 쿠폰에서 같은 요청의 재시도가 기간 오류를 받는다. 그 요청은 이미 반영된 것이라 성공이다.
     */
    private void verifyUsable(long memberCouponId, long memberId, LocalDate today) {
        String status = readStatus(memberCouponId, memberId);
        if (MemberCouponStatus.USED.name().equals(status)) {
            log.info("event=MEMBER_COUPON_ALREADY_IN_STATE memberCouponId={} status={}",
                    memberCouponId, MemberCouponStatus.USED);
            return;
        }
        if (memberCouponRepository.countWithinValidPeriod(memberCouponId, today) == 0) {
            throw new CouponException(CouponErrorCode.NOT_USABLE_PERIOD);
        }
        throw new CouponException(CouponErrorCode.INVALID_STATUS_TRANSITION);
    }

    /**
     * 사용 철회가 0행으로 끝난 사유를 가른다.
     *
     * <p>여기서는 유효기간을 안 본다. <b>주문 취소는 쿠폰 기간이 지난 뒤에도 일어난다.</b>
     * 기간을 조건에 넣으면 늦게 취소된 주문이 쿠폰을 영영 못 돌려준다.
     */
    private void verifyAlready(long memberCouponId, long memberId, MemberCouponStatus target) {
        String status = readStatus(memberCouponId, memberId);
        if (!target.name().equals(status)) {
            throw new CouponException(CouponErrorCode.INVALID_STATUS_TRANSITION);
        }
        log.info("event=MEMBER_COUPON_ALREADY_IN_STATE memberCouponId={} status={}", memberCouponId, target);
    }

    // 이 읽기는 갱신이 0행으로 끝났을 때만 돈다. 그래서 정상 흐름에는 왕복이 하나도 안 는다
    private String readStatus(long memberCouponId, long memberId) {
        List<String> found = memberCouponRepository.findStatus(memberCouponId, memberId);
        if (found.isEmpty()) {
            throw new CouponException(CouponErrorCode.MEMBER_COUPON_NOT_FOUND);
        }
        return found.get(0);
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
         * 이 배치가 대상을 고른 뒤 갱신하기까지 사이에 사용 요청이 끼어들 수 있다.
         * 그때는 사용 쪽이 이기고 이 갱신이 그 행을 건너뛴다. 그래서 고른 수와 실제로 바꾼 수가
         * 다를 수 있고, 이력도 실제로 바뀐 것만 남겨야 상태와 안 어긋난다.
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
