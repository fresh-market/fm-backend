package com.freshmarket.coupon.domain.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.domain.exception.DataAccessFailures;
import com.freshmarket.coupon.domain.redis.CouponSeqAllocator;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 선착순 쿠폰의 <b>Redis 가 보는</b> 실시간 발급 수를 아주 짧게 캐시한다.
 *
 * <p>발급 현황 화면이 폭주하듯 폴링해도 Redis {@code GET} 이 폴링 주기가 아니라 이 TTL 주기로만
 * 나가게 하려는 것이다. 총 수량과 달리 이 값은 발급 창 안에서도 계속 바뀌므로 TTL 을
 * {@link CouponCache} 보다 훨씬 짧게 둔다.
 *
 * <p>이 클래스는 DB 를 모른다. 카운터가 없거나 Redis 가 일시적으로 응답하지 못하면 빈 값을
 * 돌려줄 뿐, 그 빈 값을 DB 로 메워도 되는지는 판단하지 않는다. <b>그 판단에는 이 쿠폰의 이벤트가
 * 지금 살아있는가라는 도메인 지식이 필요한데, 그건 이 클래스가 가진 정보가 아니다.</b>
 * 호출자({@code CouponIssuanceStatusService})가 그 지식으로 판단한다.
 *
 * <p>{@link CouponCache} 와 같은 이유로 {@code AsyncCache} + 전용 실행기를 쓴다. 값을 채우는
 * 일이 Redis 블로킹 호출이라, Caffeine 의 동기 API 를 쓰면 그 자리에서 가상 스레드가 핀된다
 * (Java 21).
 */
@Component
public class CouponIssuanceCountCache {

    // 이벤트 수를 넘지 않는 값이지만 상한을 코드로 못 박아 둔다. CouponCache 와 같은 이유다
    private static final int MAX_ENTRIES = 1024;
    // 총 수량 캐시(CouponIssueProperties.couponCacheTtl)보다 훨씬 짧다. 이 값은 발급 창 안에서도 계속 바뀐다
    private static final Duration TTL = Duration.ofSeconds(1);

    private final CouponSeqAllocator seqAllocator;
    private final AsyncCache<Long, Optional<Integer>> cache;

    // 생성자가 둘이라 스프링에게 어느 쪽으로 빈을 만들지 알려 줘야 한다
    @Autowired
    public CouponIssuanceCountCache(CouponSeqAllocator seqAllocator, Clock clock) {
        this(seqAllocator, clock, Executors.newVirtualThreadPerTaskExecutor());
    }

    // 실행기를 밖에서 받는 이 생성자는 시험용이다. CouponCache 와 같은 이유다
    CouponIssuanceCountCache(CouponSeqAllocator seqAllocator, Clock clock, Executor executor) {
        this.seqAllocator = seqAllocator;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(TTL)
                .ticker(clockTicker(clock))
                .executor(executor)
                .recordStats()
                .buildAsync();
    }

    /**
     * Redis 가 보는 실시간 발급 수. TTL 안에서는 재호출해도 Redis 를 다시 안 친다.
     *
     * @return 카운터가 없거나(이벤트 미오픈/종료 후) Redis 가 일시적으로 응답하지 못하면 빈 값
     */
    public Optional<Integer> find(long couponId) {
        return join(cache.get(couponId, (id, executor) -> CompletableFuture.supplyAsync(() -> load(id), executor)));
    }

    /*
     * 일시적이지 않은 실패(코드 버그 등)까지 빈 값으로 덮으면 그 버그가 캐시에 눌어붙어 TTL 이
     * 지날 때까지 묻히므로 그건 그대로 던진다.
     */
    private Optional<Integer> load(long couponId) {
        try {
            return seqAllocator.currentIssuedCount(couponId);
        } catch (DataAccessException e) {
            if (DataAccessFailures.isTransient(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // CouponCache.join 과 같은 이유. CompletionException 의 포장을 벗겨 그대로 내보낸다
    private static Optional<Integer> join(CompletableFuture<Optional<Integer>> future) {
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
