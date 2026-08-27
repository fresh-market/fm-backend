package com.freshmarket.coupon.domain.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.entity.Coupon;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.redis.CouponSeqInitializer;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자와 배치가 이벤트를 열고 닫는다. 요청 스레드가 도는 발급 경로와 달리 드물게 도는 관리
 * 동작이라, 이 클래스는 왕복을 아끼지 않고 정확한 쪽을 고른다.
 *
 * <p>순서가 이 클래스의 전부다. 여는 쪽은 Redis 를 세우고 스위치를 켜야 하고, 닫는 쪽은
 * 스위치를 끄고 한참 뒤에 키를 지워야 한다({@code docs/coupon/coupon.md} 3장).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponEventService {

    // 진행 중인 플러시가 결판날 때까지 배치가 기다리는 시간이다
    private static final long CLEANUP_WAIT_SECONDS = 60;

    /*
     * 배치가 얼마나 뒤처져도 따라잡을 수 있는지를 정한다.
     * 이 상한이 없으면 DB 가 지금까지 끝난 모든 이벤트를 매번 세어 비용이 이력을 따라 자란다.
     */
    private static final long CLEANUP_WINDOW_DAYS = 7;

    private final CouponRepository couponRepository;
    private final CouponSeqInitializer seqInitializer;
    private final CouponCache couponCache;
    private final Clock clock;

    /**
     * 관리자가 이벤트를 연다. 이 메서드가 카운터를 세우고 발급 스위치를 켠다.
     *
     * <p>카운터가 먼저여야 하는 이유가 있다. 스위치가 먼저 켜지면 그 틈에 들어온 요청이 카운터
     * 없는 Redis 를 쳐서, <b>재고가 멀쩡한데 요청이 혼잡 응답을 받는다.</b>
     *
     * <p>그런데도 이 메서드가 스위치를 먼저 잡는 것은 경합 때문이다. 두 관리자가 동시에 열면
     * 뒤늦은 쪽이 도는 이벤트의 카운터를 지운다. {@code activateIfInactive} 가 행을 잠가 그것을
     * 막는다.
     *
     * <p><b>트랜잭션이 두 요구를 함께 만족시킨다.</b> 그 갱신은 커밋 전까지 남에게 안 보이므로,
     * 이 메서드가 잠금을 먼저 잡아도 남이 스위치를 보는 시점에는 카운터가 이미 서 있다.
     * 리포지터리 쪽 트랜잭션에 맡기면 갱신이 그 자리에서 커밋되어 순서가 도로 뒤집힌다.
     *
     * <p>이 트랜잭션이 Redis 호출을 감싸는 동안 커넥션을 쥐고 있다. 관리자가 가끔 부르는
     * 동작이라 치르는 값이고, 요청 스레드가 도는 발급 경로에서는 같은 이유로 트랜잭션을 안 연다.
     */
    @Transactional
    public void open(long couponId) {
        Coupon coupon = findLimited(couponId);
        if (couponRepository.activateIfInactive(couponId, LocalDateTime.now(clock)) == 0) {
            // 남이 이미 열었다. 여기서 Redis 를 다시 세우면 도는 이벤트의 카운터를 지운다
            log.info("event=COUPON_EVENT_ALREADY_OPEN couponId={}", couponId);
            return;
        }
        seqInitializer.prepare(couponId, coupon.getIssueEndAt());
        couponCache.evict(couponId);
        log.info("event=COUPON_EVENT_OPENED couponId={} issueEndAt={}", couponId, coupon.getIssueEndAt());
    }

    /**
     * 관리자가 이벤트를 끈다. 소진됐거나 마감 시각이 지났을 때만 꺼진다.
     *
     * <p>이 메서드는 키를 지우지 않는다. 지우는 것은 정리 배치가 한참 뒤에 한다.
     */
    @Transactional
    public void close(long couponId) {
        Coupon coupon = findLimited(couponId);
        if (!coupon.isActive()) {
            log.info("event=COUPON_EVENT_ALREADY_CLOSED couponId={}", couponId);
            return;
        }
        if (!isClosable(coupon)) {
            throw new CouponException(CouponErrorCode.EVENT_NOT_CLOSABLE);
        }
        if (couponRepository.deactivateIfClosable(couponId, LocalDateTime.now(clock)) == 0) {
            // 위 확인과 이 갱신 사이에 남이 껐다. 결과가 같으므로 실패로 답하지 않는다
            log.info("event=COUPON_EVENT_CLOSE_RACED couponId={}", couponId);
            return;
        }
        couponCache.evict(couponId);
        log.info("event=COUPON_EVENT_CLOSED_BY_ADMIN couponId={}", couponId);
    }

    /*
     * 관리자가 끌 수 있는 것은 둘 중 하나가 참일 때다.
     * 마감 시각이 지났거나, 행이 총량만큼 실재해 회수할 번호가 없거나다.
     */
    private boolean isClosable(Coupon coupon) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (coupon.getIssueEndAt() != null && !now.isBefore(coupon.getIssueEndAt())) {
            return true;
        }
        return couponRepository.countIssued(coupon.getId()) >= coupon.getTotalQuantity();
    }

    /**
     * 관리자가 발급 시각을 바꾼다. 아직 시작하지 않은 이벤트만 바뀐다.
     *
     * <p>이미 켜진 이벤트라면 이 메서드가 카운터의 만료 시각도 다시 건다. 그러지 않으면 옛
     * 마감으로 계산된 TTL 이 남아 <b>키가 새 마감보다 먼저 사라진다.</b>
     */
    @Transactional
    public void changeIssuePeriod(long couponId, LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
        Coupon coupon = findLimited(couponId);
        int changed = couponRepository.updateIssuePeriodIfNotStarted(
                couponId, issueStartAt, issueEndAt, LocalDateTime.now(clock));
        if (changed == 0) {
            throw new CouponException(CouponErrorCode.ISSUE_PERIOD_LOCKED);
        }
        if (coupon.isActive()) {
            seqInitializer.applyTtl(couponId, issueEndAt);
        }
        couponCache.evict(couponId);
        log.info("event=COUPON_ISSUE_PERIOD_CHANGED couponId={} start={} end={}",
                couponId, issueStartAt, issueEndAt);
    }

    /** 배치가 마감 시각이 지난 이벤트의 스위치를 끈다. 이 메서드는 키를 안 지운다. */
    public int closeFinishedEvents() {
        int closed = couponRepository.deactivateFinishedEvents(LocalDateTime.now(clock));
        if (closed > 0) {
            log.info("event=COUPON_EVENT_CLOSED_BY_BATCH count={}", closed);
        }
        return closed;
    }

    /**
     * 배치가 꺼진 지 충분히 지난 이벤트의 발급 수를 맞추고 키를 치운다.
     *
     * <p>대상도 대기도 DB 조건이 정한다. 그래서 이 메서드에는 기다리는 코드가 없다.
     *
     * <p>이 메서드에 {@code @Transactional} 이 없다. 쿠폰마다 DB 와 Redis 를 번갈아 만지는데,
     * 그것을 한 트랜잭션으로 묶으면 Redis 를 기다리는 내내 커넥션을 쥔다.
     */
    public int cleanupClosedEvents() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> targets = couponRepository.findCleanupTargets(
                now.minusSeconds(CLEANUP_WAIT_SECONDS), now.minus(CLEANUP_WINDOW_DAYS, ChronoUnit.DAYS));
        for (Long couponId : targets) {
            couponRepository.syncIssuedQuantity(couponId);
            seqInitializer.clear(couponId);
            log.info("event=COUPON_EVENT_CLEANED couponId={}", couponId);
        }
        return targets.size();
    }

    private Coupon findLimited(long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        if (!coupon.isLimited()) {
            throw new CouponException(CouponErrorCode.NOT_LIMITED);
        }
        return coupon;
    }
}
