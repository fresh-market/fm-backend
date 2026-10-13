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
 * ({@code docs/coupon/budget-breakdown-2026-10-10.md} 4장).
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
     * 다만 재건이 도는 동안 그 이벤트의 발급은 멈춰 있으므로 무한정 늘릴 수는 없다.
     *
     * 1초에서 3초로 올렸다. 2026-10-13 V-4(서버 1대 종료 + 캐시 페일오버)에서 재건이 세 번
     * 전부 QueryTimeoutException 으로 죽었고, free 를 못 세워 주인 잃은 번호 144개가 재고로
     * 돌아오지 않았다. 같은 회차의 결번이 144 다.
     *
     *   11:46:24.321  REBUILD_RETRY   attempt=1  reason=QueryTimeoutException
     *   11:46:28.117  REBUILD_RETRY   attempt=2  reason=QueryTimeoutException
     *   11:46:29.517  REBUILD_FAILED  attempts=3
     *
     * 복합 장애라 한 대가 죽고 남은 둘에 부하가 몰린 채, 승격 직후의 새 프라이머리가 1초를
     * 넘겼다. 2026-10-10 J-2 에서 같은 모양이 100ms 로 났고 그때 1초로 올렸는데 모자랐다.
     *
     * 3초인 근거는 rebuild-contribute-wait 와 같은 값이라는 것이다. 재건은 이미 남의 큐를
     * 기다리느라 그만큼은 멈춰 있기로 한 설계이므로, 명령 하나의 상한을 그보다 크게 둘 이유가
     * 없고 그보다 작게 두면 기다림을 다 쓰기 전에 명령이 먼저 죽는다.
     *
     * 최악은 재시도 셋을 합쳐 약 10초다 (3초 x 3 + 백오프 200ms x 1,2). 재건 락 TTL 30초
     * 안에 들고, 세 번 다 죽는 것보다 그 10초가 낫다는 판단이다. V-4 가 그 비교를 보여 준다.
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
