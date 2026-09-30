package com.freshmarket.coupon.internal.redis;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponRepository;

import io.lettuce.core.event.connection.ConnectionActivatedEvent;
import io.lettuce.core.event.connection.DisconnectedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

/**
 * Redis 연결이 끊겼다 다시 붙으면 열려 있는 이벤트의 키를 다시 세우게 한다.
 *
 * <p><b>승격은 키를 지우지 않는다.</b> 복제가 밀린 채 승격되면 카운터는 살아 있고 값만 뒤로
 * 가므로, 스크립트가 {@code -2} 를 안 내고 기존 방아쇠가 영영 안 걸린다. 그래서 재연결 자체를
 * 두 번째 신호로 둔다 ({@code docs/coupon/rebuild-redesign.md} 2장).
 *
 * <p><b>처음 붙는 것은 신호가 아니다.</b> 기동과 배포에도 활성화 이벤트가 오므로, 끊긴 것을 본
 * 뒤의 활성화만 센다. 안 가리면 배포마다 열려 있는 이벤트의 발급을 멈춘다.
 *
 * <p><b>이벤트는 연결마다 온다.</b> 이 Valkey 는 인증 캐시도 함께 쓰므로 한 번의 단절에 여러 개가
 * 몰린다. 그래서 최소 간격을 두고, 쿠폰별 중복은 {@link CouponSeqRebuildTrigger} 가 막는다.
 */
@Slf4j
@Component
public class CouponSeqReconnectWatcher {

    private final LettuceConnectionFactory connectionFactory;
    private final CouponRepository couponRepository;
    private final CouponSeqRebuildTrigger trigger;
    private final boolean enabled;
    private final Duration minInterval;
    private final Counter fired;

    // 끊긴 것을 본 뒤의 활성화만 신호로 센다
    private final AtomicBoolean sawDisconnect = new AtomicBoolean(false);
    private final AtomicLong lastFiredAtNanos = new AtomicLong(Long.MIN_VALUE);

    private Disposable subscription;

    public CouponSeqReconnectWatcher(LettuceConnectionFactory connectionFactory,
                                     CouponRepository couponRepository,
                                     CouponSeqRebuildTrigger trigger,
                                     CouponIssueProperties properties,
                                     MeterRegistry registry) {
        this.connectionFactory = connectionFactory;
        this.couponRepository = couponRepository;
        this.trigger = trigger;
        this.enabled = properties.rebuildOnReconnect();
        this.minInterval = properties.rebuildOnReconnectMinInterval();
        this.fired = Counter.builder("coupon.seq.reconnect.fired")
                .description("재연결을 신호로 재건을 띄운 횟수")
                .register(registry);
    }

    @PostConstruct
    void subscribe() {
        if (!enabled) {
            log.info("event=COUPON_SEQ_RECONNECT_WATCH_DISABLED");
            return;
        }
        subscription = connectionFactory.getClientResources().eventBus().get()
                .subscribe(this::onEvent, this::onError);
        log.info("event=COUPON_SEQ_RECONNECT_WATCH_STARTED minIntervalMillis={}", minInterval.toMillis());
    }

    @PreDestroy
    void unsubscribe() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    private void onEvent(Object event) {
        if (event instanceof DisconnectedEvent) {
            sawDisconnect.set(true);
            return;
        }
        if (event instanceof ConnectionActivatedEvent && sawDisconnect.compareAndSet(true, false)) {
            reconcileOpenEvents();
        }
    }

    /*
     * 구독이 끊기면 다음 승격을 못 본다. 살려 두고 남긴다.
     *
     * 다시 구독하지는 않는다. 이 버스는 연결이 사는 동안 함께 사는 것이고, 오류가 나는 상황이면
     * 그 연결 자체가 이미 문제다. 기존 방아쇠가 남아 있어 카운터가 사라지는 쪽은 계속 걸린다.
     */
    private void onError(Throwable e) {
        log.error("event=COUPON_SEQ_RECONNECT_WATCH_FAILED", e);
    }

    private void reconcileOpenEvents() {
        long now = System.nanoTime();
        long last = lastFiredAtNanos.get();
        if (last != Long.MIN_VALUE && now - last < minInterval.toNanos()) {
            return;
        }
        if (!lastFiredAtNanos.compareAndSet(last, now)) {
            // 같은 단절로 들어온 다른 연결이 방금 띄웠다
            return;
        }

        List<Long> couponIds;
        try {
            couponIds = couponRepository.findOpenLimitedEvents();
        } catch (RuntimeException e) {
            // DB 에 못 닿으면 재건도 못 한다. 다음 재연결이나 다음 -2 가 다시 띄운다
            log.warn("event=COUPON_SEQ_RECONNECT_LOOKUP_FAILED", e);
            return;
        }
        if (couponIds.isEmpty()) {
            return;
        }
        log.warn("event=COUPON_SEQ_RECONNECT_DETECTED coupons={}", couponIds.size());
        for (Long couponId : couponIds) {
            fired.increment();
            trigger.suspect(couponId);
        }
    }
}
