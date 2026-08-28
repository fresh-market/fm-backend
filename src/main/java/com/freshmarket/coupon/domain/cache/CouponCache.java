package com.freshmarket.coupon.domain.cache;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 요청 스레드가 자격 확인에 쓰는 쿠폰을 이 JVM 안에 잠깐 들고 있는다. 요청마다 돌던 DB 조회
 * 하나를 없애는 것이 목적이다.
 *
 * <p>이것이 성립하는 근거는 관리자 API 가 세운 규칙이다. 발급 창 안에서는 발급 시각도, 총량도,
 * 대상 등급도, 스위치도 아무도 못 바꾼다({@code docs/coupon/coupon.md} 3장). 그 규칙이 없으면
 * 이 캐시는 거짓말을 한다.
 */
@Component
public class CouponCache {

    /*
     * 켜진 쿠폰만 담으므로 항목 수는 도는 이벤트 수만큼이다.
     * 상한은 그래도 안 자란다는 것을 코드로 못 박아 두는 것이고, 평소에 닿지 않는다.
     */
    private static final int MAX_ENTRIES = 1024;

    private final CouponRepository couponRepository;
    private final AsyncCache<Long, CachedCoupon> cache;

    // 생성자가 둘이라 스프링에게 어느 쪽으로 주입할지 알려야 한다
    @Autowired
    public CouponCache(CouponRepository couponRepository, CouponIssueProperties properties, Clock clock) {
        /*
         * 값을 읽는 일이 JDBC 블로킹이라 Caffeine 의 기본값인 공용 ForkJoinPool 에 두면 안 된다.
         * 그 풀은 CPU 작업 기준으로 크기가 잡혀 있어, 거기서 막히면 다른 작업까지 굶는다.
         */
        this(couponRepository, properties, clock, Executors.newVirtualThreadPerTaskExecutor());
    }

    /*
     * 실행기를 받는 생성자는 시험용이다.
     * AsyncCache 는 future 가 완료될 때 쓰기 시각을 찍는데, 그 콜백이 다른 스레드에서 돌면
     * 시험이 시계를 민 시점과 찍히는 시점의 순서가 안 정해진다. 같은 스레드에서 돌리면 정해진다.
     */
    CouponCache(CouponRepository couponRepository, CouponIssueProperties properties, Clock clock,
                Executor executor) {
        this.couponRepository = couponRepository;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(properties.couponCacheTtl())
                // 시험이 TTL 경계를 재려면 시간을 밀 수 있어야 한다. 주입받은 Clock 을 그대로 따른다
                .ticker(clockTicker(clock))
                .executor(executor)
                .recordStats()
                .buildAsync();
    }

    /**
     * 쿠폰을 찾는다. 켜져 있으면 다음 호출부터 DB 를 안 친다.
     *
     * <p>맵에 담기는 값이 결과가 아니라 {@code CompletableFuture} 다. 그래서
     * {@code ConcurrentHashMap} 이 버킷 모니터를 쥐고 있는 동안 하는 일이 <b>future 하나를 만들어
     * 넣는 것뿐</b>이고, 실제 DB 읽기는 그 바깥에서 돈다. 요청 스레드는 {@code join()} 으로
     * 기다리는데 그것이 {@code LockSupport.park} 라 <b>가상 스레드가 캐리어를 반납한다.</b>
     *
     * <p>값을 계산하는 함수를 모니터 안에서 돌리는 API(Caffeine 의 동기 {@code get} 이든 직접 쓴
     * {@code computeIfAbsent} 든)를 쓰면 그 자리에서 캐리어가 핀된다(Java 21).
     *
     * <p>덤으로 먼저 온 하나만 읽고 나머지는 그 future 를 함께 기다린다. 캐시가 빈 순간에
     * 여러 요청 스레드가 같이 DB 를 치지 않는다.
     */
    public Optional<CachedCoupon> find(long couponId) {
        CompletableFuture<CachedCoupon> hit = cache.getIfPresent(couponId);
        if (hit != null) {
            return Optional.ofNullable(join(hit));
        }

        CachedCoupon loaded = join(cache.get(couponId,
                (id, executor) -> CompletableFuture.supplyAsync(() -> load(id), executor)));

        /*
         * 켜진 쿠폰만 남긴다.
         *
         * 얼어붙는다는 보장이 켜져 있는 동안에만 성립하기 때문이다. 꺼진 값을 담으면 관리자가
         * 여는 순간부터 TTL 만큼 그 인스턴스가 "지금은 발급할 수 없다" 로 답한다. 이벤트가
         * 열리는 바로 그 순간에 사람이 가장 많이 몰리므로 그 창을 만들면 안 된다.
         *
         * 반대 방향은 해롭지 않다. 마감으로 꺼진 뒤 TTL 만큼 더 받아도 수량은 순번이 막고
         * 몇 건이 늦게 발급될 뿐이다.
         */
        if (loaded == null || !loaded.active()) {
            cache.synchronous().invalidate(couponId);
        }
        return Optional.ofNullable(loaded);
    }

    /** 관리자가 이벤트를 열고 닫거나 시각을 바꾼 뒤에 부른다. 이 인스턴스의 사본만 지운다. */
    public void evict(long couponId) {
        cache.synchronous().invalidate(couponId);
    }

    private CachedCoupon load(long couponId) {
        return couponRepository.findById(couponId).map(CachedCoupon::from).orElse(null);
    }

    /*
     * 이 메서드가 읽다 난 예외를 CompletableFuture 의 포장 없이 그대로 내보낸다.
     * 호출자가 CompletionException 을 벗겨 가며 원인을 찾게 두면, 이 클래스가 안에서 future 를
     * 쓴다는 사실이 계약으로 새어 나간다.
     */
    private static CachedCoupon join(CompletableFuture<CachedCoupon> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static Ticker clockTicker(Clock clock) {
        return () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
    }
}
