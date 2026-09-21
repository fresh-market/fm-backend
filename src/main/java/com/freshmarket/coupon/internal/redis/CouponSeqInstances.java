package com.freshmarket.coupon.internal.redis;

import java.time.Duration;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 지금 큐를 쥐고 있는 인스턴스의 명부다. 재건 주도자가 <b>몇 대를 기다려야 하는지</b>를 여기서 안다.
 *
 * <p><b>이것이 없으면 주도자는 시간으로 때울 수밖에 없다.</b> {@code rebuild:queued} 에 백 건이
 * 들어와 있을 때 그것이 세 대가 다 올린 것인지 한 대만 올린 것인지 구분할 방법이 없기 때문이다.
 * 그래서 예전에는 고정으로 3초를 잤고, 그 3초가 재건 정지 시간의 대부분이었다.
 *
 * <p><b>갱신을 플러시 경로에 얹는다.</b> 전용 스레드를 두지 않는 것은 이 저장소가
 * {@code @Scheduled} 를 배치 프로필로 묶어 두었기 때문만이 아니다. 플러시 루프는 <b>큐에 티켓이
 * 있을 때만</b> 한 바퀴를 마치므로, 갱신이 신선하다는 것이 곧 "이 인스턴스가 지금 큐를 쥐고
 * 있다" 와 같아진다. <b>기다려야 할 대상과 등록된 대상이 저절로 맞는다.</b>
 *
 * <p>주기 등록을 하면 오히려 이 성질이 깨진다. 큐가 빈 인스턴스까지 기다리게 되기 때문이다.
 *
 * <p><b>식별자는 JVM 이 뜰 때 만든다.</b> 환경 변수에 기대지 않는다. 그 값이 안 들어오면 모든
 * 인스턴스가 같은 이름으로 등록되어 <b>명부가 늘 한 대로 보이고, 주도자가 아무도 안 기다린다.</b>
 * 조용히 망가지는 모양이라 바깥 설정에 맡기지 않는다. 재시작하면 큐도 새것이므로 식별자가 새로
 * 생기는 것이 의미상으로도 맞다.
 */
@Slf4j
@Component
class CouponSeqInstances {

    private static final String KEY = "coupon:instances";

    /*
     * 갱신 주기다. 플러시가 배치마다 부르므로 그대로 두면 초당 오십 번 쓴다.
     * 명부는 "살아 있나" 만 보는 것이라 그렇게 자주 쓸 이유가 없다.
     */
    private static final Duration REFRESH_EVERY = Duration.ofSeconds(2);

    /*
     * 이 시간이 지난 등록은 죽은 것으로 본다.
     *
     * 갱신 주기보다 넉넉해야 한다. 짧으면 살아 있는데도 명부에서 빠지고, 그러면 주도자가 그
     * 인스턴스의 큐를 안 기다린 채 진행해 그 번호들을 남에게 다시 내준다. 명부의 실수는
     * "덜 기다리는" 쪽이 위험하므로 넉넉한 쪽으로 잡는다.
     */
    private static final Duration STALE_AFTER = Duration.ofSeconds(10);

    /*
     * 이 시간이 지난 등록은 아예 치운다.
     *
     * 식별자를 JVM 마다 새로 만들므로 배포와 교체가 있을 때마다 항목이 하나씩 는다. 치우지
     * 않으면 명부가 끝없이 자란다. live 가 구간만 세어 답은 계속 맞으므로 아무도 안 알아챈다.
     *
     * STALE_AFTER 보다 훨씬 넉넉해야 한다. live 가 세는 구간을 치우면 살아 있는 인스턴스를
     * 지우고, 그러면 주도자가 그 인스턴스를 안 기다린 채 키를 세운다.
     */
    private static final Duration PRUNE_AFTER = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final String id = UUID.randomUUID().toString();

    /*
     * nanoTime 의 기준점은 임의라 차이로만 비교해야 한다.
     * MIN_VALUE 로 두면 now 와의 뺄셈이 오버플로해 음수가 되고, 그러면 첫 호출이 "방금
     * 갱신했다" 로 읽혀 명부에 아무도 안 올라간다.
     */
    private volatile long refreshedAtNanos = System.nanoTime() - REFRESH_EVERY.toNanos();

    CouponSeqInstances(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    String id() {
        return id;
    }

    /**
     * 이 인스턴스가 살아 있고 큐를 쥐고 있다고 알린다. 플러시가 배치마다 부른다.
     *
     * <p><b>실제로 쓰는 것은 주기마다 한 번뿐이다.</b> 나머지 호출은 시계만 보고 돌아간다.
     *
     * <p>실패해도 삼킨다. 부른 쪽은 방금 배치를 성공으로 끝낸 플러시 스레드이고, 명부 하나
     * 때문에 그 배치가 실패로 뒤집히면 안 된다.
     */
    void refresh() {
        long now = System.nanoTime();
        if (now - refreshedAtNanos < REFRESH_EVERY.toNanos()) {
            return;
        }
        refreshedAtNanos = now;
        try {
            long millis = System.currentTimeMillis();
            redisTemplate.opsForZSet().add(KEY, id, millis);
            // 갱신하는 김에 오래된 것을 치운다. 이 호출이 주기마다 한 번이라 비용이 없다
            redisTemplate.opsForZSet().removeRangeByScore(KEY, 0, millis - PRUNE_AFTER.toMillis());
        } catch (DataAccessException e) {
            log.debug("event=COUPON_SEQ_INSTANCE_REFRESH_FAILED id={}", id, e);
        }
    }

    /**
     * 지금 살아 있는 인스턴스 수다.
     *
     * <p><b>못 세면 0 을 준다.</b> 부르는 쪽은 그때 조기 종료를 포기하고 정해진 시간을 다
     * 기다려야 한다. 모르는 채로 일찍 끝내는 것이 이 기능에서 가장 나쁜 결과다.
     */
    int live() {
        try {
            Long count = redisTemplate.opsForZSet()
                    .count(KEY, System.currentTimeMillis() - STALE_AFTER.toMillis(), Double.MAX_VALUE);
            return count == null ? 0 : count.intValue();
        } catch (DataAccessException e) {
            log.debug("event=COUPON_SEQ_INSTANCE_COUNT_FAILED", e);
            return 0;
        }
    }
}
