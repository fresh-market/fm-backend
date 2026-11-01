package com.freshmarket.coupon.internal.redis;

import java.time.Duration;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 재건이 쓰는 Redis 통로다. 발급이 쓰는 것과 타임아웃만 다르다.
 *
 * <p><b>발급의 타임아웃을 재건이 같이 쓰면 안 된다.</b> 발급은
 * {@code spring.data.redis.timeout: 100ms} 를 쓰는데 그 값은 commit-wait 예산에서 역산한
 * 것이고, 요청 하나가 그 안에 끝나야 하기 때문에 짧다. 재건은 그 예산 밖에서 도는 일이라
 * 같은 상한을 받을 이유가 없다.
 *
 * <p><b>그 상한이 실제로 재건을 죽였다.</b> 2026-10-10 J-2 회차에서 캐시 페일오버 뒤 재건이
 * {@code RedisCommandTimeoutException: Command timed out after 100 millisecond(s)} 로 끝났다.
 * 승격 직후의 새 프라이머리가 아직 부하를 받는 중이라 재건의 명령이 100ms 를 넘겼다. 재건이
 * 못 돌아 카운터가 뒤로 밀린 채 남았고, 재고 10,000장 중 1,030장이 안 나갔다
 * ({@code docs/coupon/rebuild-v1/budget-breakdown.md} 4장).
 *
 * <p><b>커넥션이 하나 늘어나는 것이 대가다.</b> 전용 3대에서 Valkey 커넥션 셋이다. 재건이
 * 드물게 도는 일이라 이 커넥션은 거의 놀지만, 타임아웃이 팩토리 단위 설정이라 커넥션을
 * 나누지 않고서는 상한을 나눌 수 없다.
 */
@Slf4j
@Component
public class CouponSeqRebuildRedis {

    /*
     * 재건의 Redis 명령 하나에 주는 상한이다.
     *
     * 발급의 100ms 보다 서른 배인데, 재건은 요청 예산 밖이라 이 값이 응답 지연으로 새지 않는다.
     *
     * 1초로는 승격 직후를 못 넘긴다. 두 회차가 같은 모양으로 죽었다.
     *
     *   2026-10-13 V-4   복합 장애.  재시도 셋이 전부 QueryTimeoutException.  결번 144
     *   2026-10-14 Y-3   복합 장애.  같은 모양.  재건이 한 번도 완주하지 못했다
     *
     *   11:46:24.321  REBUILD_RETRY   attempt=1  reason=QueryTimeoutException
     *   11:46:28.117  REBUILD_RETRY   attempt=2  reason=QueryTimeoutException
     *   11:46:29.517  REBUILD_FAILED  attempts=3
     *
     * 복합 장애라 한 대가 죽고 남은 둘에 부하가 몰린 채, 승격 직후의 새 프라이머리가 1초를
     * 넘겼다. 2026-10-10 J-2 에서 같은 모양이 100ms 로 났고 그때 1초로 올렸는데 모자랐다.
     *
     * 올리는 비용이 전보다 싸졌다. 전에는 재건이 도는 동안 그 이벤트의 발급이 멈춰 있어서
     * 이 값이 곧 발급 정지 시간이었다. #242 가 그 멈춤을 걷어냈으므로 이제 이 값은 재건
     * 자신이 끝나는 시점만 늦추고 발급은 그동안 계속 돈다.
     *
     * 그래서 상한을 정하는 것은 발급이 아니라 재건 락의 수명이다. 최악이 재시도 셋을 합쳐
     * 약 10초이고(3초 x 3 + 백오프 200ms x 1,2) 락 TTL 30초 안에 든다. 세 번 다 죽어
     * free 를 한 번도 못 세우는 것보다 그 10초가 낫다.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate template;

    /*
     * 원본 팩토리에서 접속 설정을 베낀다.
     *
     * 호스트와 포트를 따로 읽지 않는 이유가 있다. 비밀번호와 데이터베이스 번호와 SSL 까지
     * 같이 와야 하고, 그것들이 갈라지면 재건만 접속에 실패하는 상태가 된다.
     */
    public CouponSeqRebuildRedis(LettuceConnectionFactory origin) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(TIMEOUT)
                .build();
        this.factory = new LettuceConnectionFactory(origin.getStandaloneConfiguration(), clientConfig);
        this.factory.afterPropertiesSet();
        this.template = new StringRedisTemplate(this.factory);
        log.info("event=COUPON_SEQ_REBUILD_REDIS_READY timeoutMillis={}", TIMEOUT.toMillis());
    }

    public StringRedisTemplate template() {
        return template;
    }

    @PreDestroy
    void close() {
        factory.destroy();
    }
}
