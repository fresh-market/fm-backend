package com.freshmarket.coupon.domain.service;

import java.time.Clock;
import java.time.LocalDateTime;
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
 * 관리자와 배치가 선착순 이벤트를 열고 닫는 자리다. 요청 스레드가 도는 발급 경로와 달리 아주
 * 드물게 도는 관리 동작이라, 이 클래스는 왕복을 아끼기보다 정확한 쪽을 고른다.
 *
 * <p><b>순서가 이 클래스의 전부다.</b> 여는 쪽은 Redis 카운터를 세우고 나서 발급 스위치를 켜야
 * 하고, 닫는 쪽은 마감에서 대기 시간이 지난 뒤에 스위치를 끄면서 발급 수를 맞추고 키를 지워야
 * 한다({@code docs/coupon/coupon.md} 3장).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponEventService {

    /*
     * 마감 뒤 이만큼 지나야 이벤트를 끈다.
     * 진행 중인 플러시가 결판나기를 기다리는 시간이고, 그것이 끝나야 발급 행 수가 멈춘다.
     * 행 수가 멈춰야 끄면서 같은 트랜잭션에서 발급 수를 맞출 수 있다.
     */
    private static final long CLOSE_WAIT_SECONDS = 60;

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
     * <p>그래서 이 트랜잭션 안의 Redis 왕복은 카운터를 세우는 것 하나뿐이다. 관리자가 가끔
     * 부르는 동작이라 그 하나는 치르는 값이고, 요청 스레드가 도는 발급 경로에서는 같은 이유로
     * 트랜잭션을 아예 안 연다.
     */
    @Transactional
    public void open(long couponId) {
        Coupon coupon = findLimited(couponId);
        if (couponRepository.activateIfInactive(couponId, LocalDateTime.now(clock)) == 0) {
            // 다른 관리자가 이미 열었다. 여기서 Redis 를 다시 세우면 돌고 있는 이벤트의 카운터를 0 으로 지운다
            log.info("event=COUPON_EVENT_ALREADY_OPEN couponId={}", couponId);
            return;
        }
        seqInitializer.prepare(couponId, coupon.getIssueEndAt());
        couponCache.evict(couponId);
        log.info("event=COUPON_EVENT_OPENED couponId={} issueEndAt={}", couponId, coupon.getIssueEndAt());
    }

    /**
     * 관리자가 이벤트를 끈다. <b>마감에서 대기 시간이 지나야 꺼진다.</b>
     *
     * <p>소진으로는 못 끈다. {@code free} 에 반납된 번호가 남아 있으면 스크립트가 그것을 다시
     * 내주므로 소진이 최종이 아니고, 소진 뒤에도 스위치가 켜져 있어야 <b>요청이 올 때 도는
     * 회수가 묶인 번호를 되살린다.</b> 관리자가 그때 끄면 그 번호가 그대로 죽는다.
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
        if (!closeAndSettle(couponId)) {
            // 위 확인과 이 갱신 사이에 배치나 다른 관리자가 껐다. 결과가 같으므로 실패로 답하지 않는다
            log.info("event=COUPON_EVENT_CLOSE_RACED couponId={}", couponId);
            return;
        }
        couponCache.evict(couponId);
        log.info("event=COUPON_EVENT_CLOSED_BY_ADMIN couponId={}", couponId);
    }

    // 관리자가 끌 수 있는 때는 마감에서 대기 시간이 지난 뒤다. 배치가 보는 조건과 같은 식이어야 한다
    private boolean isClosable(Coupon coupon) {
        return coupon.getIssueEndAt() != null
                && !LocalDateTime.now(clock).isBefore(coupon.getIssueEndAt().plusSeconds(CLOSE_WAIT_SECONDS));
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

    /** 배치가 끌 이벤트를 찾는다. 반복은 배치가 들고, 쿠폰 하나가 트랜잭션 하나다. */
    public List<Long> findClosableEvents() {
        return couponRepository.findClosableEvents(closableBefore());
    }

    /**
     * 배치가 이벤트 하나를 끝낸다. 끄기와 발급 수 맞추기와 키 치우기가 한 트랜잭션이다.
     *
     * <p><b>반복을 이 클래스가 갖지 않는다.</b> 같은 클래스의 메서드를 부르면 프록시를 안 거쳐
     * 이 경계가 사라지고, 그러면 껐는데 발급 수가 안 맞은 행이 남는다. 그 행은 다음 실행에서
     * 이미 꺼져 있어 후보가 아니므로 <b>아무도 다시 안 맞춘다.</b>
     *
     * @return 이 호출이 껐으면 true. 남이 먼저 껐거나 아직 때가 아니면 false
     */
    @Transactional
    public boolean closeIfDue(long couponId) {
        if (!closeAndSettle(couponId)) {
            return false;
        }
        log.info("event=COUPON_EVENT_CLOSED_BY_BATCH couponId={}", couponId);
        return true;
    }

    /**
     * 끄고, 발급 수를 실제 행 수로 맞추고, 키를 치운다. 관리자와 배치가 같은 순서를 지난다.
     *
     * <p>셋이 한 트랜잭션인 것이 요점이다. 나누면 껐는데 발급 수가 안 맞은 상태가 존재하고,
     * 그 상태를 나중에 찾아 고치는 장치가 따로 필요해진다.
     *
     * <p><b>키 치우기는 유일한 정리가 아니라 즉시 회수다.</b> 네 키가 모두 마감에서 온 수명을
     * 들고 있어 이 호출이 없어도 Redis 가 스스로 지운다. {@code counter} 는 준비 단계가 걸고,
     * {@code seq} 와 {@code pending} 은 순번 확보 스크립트가 만들면서 물려받고, {@code free} 는
     * 반납이 만들면서 물려받는다.
     *
     * <p>그런데도 이 트랜잭션 안에 두는 것은 <b>껐는데 키가 남은 중간 상태를 아예 안 만들려는
     * 것</b>이다. Redis 가 죽으면 끄기까지 되돌아가고 다음 주기가 다시 시도한다. 그동안 마감이
     * 지난 요청은 기간 검사가 이미 막으므로 잃는 것이 없다. 배치가 10분마다 도는 경로라 이
     * 왕복이 커넥션을 쥐는 값도 치를 만하다.
     */
    private boolean closeAndSettle(long couponId) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (couponRepository.deactivateIfClosable(couponId, closableBefore(), now) == 0) {
            return false;
        }
        couponRepository.syncIssuedQuantity(couponId);
        seqInitializer.clear(couponId);
        return true;
    }

    private LocalDateTime closableBefore() {
        return LocalDateTime.now(clock).minusSeconds(CLOSE_WAIT_SECONDS);
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
