package com.freshmarket.coupon.domain.cache;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 요청 스레드가 자격 확인에 쓰는 쿠폰을 이 JVM 안에 잠깐 들고 있는다. 요청마다 돌던 DB 조회
 * 하나를 없애는 것이 목적이다.
 *
 * <p>이것이 성립하는 근거는 관리자 API 가 세운 규칙이다. 발급 창 안에서는 발급 시각도, 총량도,
 * 대상 등급도, 스위치도 아무도 못 바꾼다({@code docs/coupon/coupon.md} 3장). 그 규칙이 없으면
 * 이 캐시는 거짓말을 한다.
 */
@Slf4j
@Component
public class CouponCache {

    /*
     * 켜진 쿠폰만 담는다.
     *
     * 얼어붙는다는 보장이 켜져 있는 동안에만 성립하기 때문이다. 꺼진 값을 담으면 관리자가 여는
     * 순간부터 TTL 만큼 그 인스턴스가 "지금은 발급할 수 없다" 로 답한다. 이벤트가 열리는 바로
     * 그 순간에 사람이 가장 많이 몰리므로 그 창을 만들면 안 된다.
     *
     * 반대 방향은 해롭지 않다. 마감으로 꺼진 뒤 TTL 만큼 더 받아도 수량은 순번이 막고 몇 건이
     * 늦게 발급될 뿐이다.
     */
    private static final int MAX_ENTRIES = 1024;

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final CouponRepository couponRepository;
    private final Clock clock;
    private final long ttlMillis;

    public CouponCache(CouponRepository couponRepository, CouponIssueProperties properties, Clock clock) {
        this.couponRepository = couponRepository;
        this.clock = clock;
        this.ttlMillis = properties.couponCacheTtl().toMillis();
    }

    /**
     * 쿠폰을 찾는다. 켜져 있으면 다음 호출부터 DB 를 안 친다.
     *
     * <p>{@code computeIfAbsent} 를 쓰지 않는다. {@code ConcurrentHashMap} 이 그 함수를 도는
     * 동안 버킷 모니터를 잡으므로, 요청 스레드가 그 안에서 DB 를 기다리면 <b>가상 스레드가
     * 캐리어를 못 놓고 핀된다</b>(Java 21). 이 메서드는 모니터를 안 쥔 채로 읽고 결과만 넣는다.
     *
     * <p>대가는 캐시가 빈 순간 여러 요청 스레드가 같이 DB 를 읽는 것이다. 그 읽기는 멱등이고
     * 결과가 같으며, 그 몰림은 쿠폰당 TTL 마다 한 번뿐이다.
     */
    public Optional<CachedCoupon> find(long couponId) {
        long now = clock.millis();
        Entry hit = entries.get(couponId);
        if (hit != null && hit.expiresAt() > now) {
            return Optional.of(hit.coupon());
        }

        Optional<CachedCoupon> loaded = couponRepository.findById(couponId).map(CachedCoupon::from);
        loaded.filter(CachedCoupon::active).ifPresent(coupon -> put(couponId, coupon, now));
        return loaded;
    }

    /** 관리자가 이벤트를 열고 닫거나 시각을 바꾼 뒤에 부른다. 이 인스턴스의 사본만 지운다. */
    public void evict(long couponId) {
        entries.remove(couponId);
    }

    /*
     * 켜진 쿠폰만 들어오므로 항목 수는 도는 이벤트 수만큼이다.
     * 상한은 그래도 안 자란다는 것을 코드로 못 박아 두는 것이고, 걸리면 통째로 비워 다시 채운다.
     */
    private void put(long couponId, CachedCoupon coupon, long now) {
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(couponId)) {
            log.warn("event=COUPON_CACHE_RESET size={}", entries.size());
            entries.clear();
        }
        entries.put(couponId, new Entry(coupon, now + ttlMillis));
    }

    private record Entry(CachedCoupon coupon, long expiresAt) {
    }
}
