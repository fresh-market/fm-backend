package com.freshmarket.coupon.internal.warmup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/*
 * 워밍업이 기동을 막지 않는지, 그리고 첫 Redis 커넥션 실패를 넘기는지 본다.
 */
class CouponWarmupRunnerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final WebServerApplicationContext webServer = mock(WebServerApplicationContext.class);

    private CouponWarmupRunner runner(CouponWarmupProperties properties) {
        return new CouponWarmupRunner(properties, jwt, redis, webServer);
    }

    private static CouponWarmupProperties enabled(int requests) {
        return new CouponWarmupProperties(true, 1000000L, requests, 2, Duration.ofSeconds(2));
    }

    // 꺼져 있으면 Redis 도 안 건드린다
    @Test
    void 꺼져_있으면_아무것도_하지_않는다() {
        runner(new CouponWarmupProperties(false, 1L, 10, 2, Duration.ofSeconds(1)))
                .run(new DefaultApplicationArguments());

        verify(redis, never()).hasKey(anyString());
    }

    /*
     * 이 JVM 의 첫 Redis 명령은 DNS 조회와 핸드셰이크까지 명령 타임아웃 안에 끝내야 해서
     * 차가운 JVM 에서 자주 실패한다. 한 번 실패해도 다시 붙어야 워밍업이 돈다.
     */
    @Test
    void 첫_커넥션이_실패해도_다시_붙는다() {
        when(redis.hasKey(anyString()))
                .thenThrow(new QueryTimeoutException("Connection initialization timed out"))
                .thenThrow(new QueryTimeoutException("Connection initialization timed out"))
                .thenReturn(false);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(webServer.getWebServer()).thenThrow(new IllegalStateException("여기까지 왔으면 충분하다"));

        runner(enabled(1)).run(new DefaultApplicationArguments());

        verify(redis, times(3)).hasKey(anyString());
        // 붙은 뒤에야 카운터를 소진으로 세운다
        verify(redis).opsForValue();
    }

    /*
     * 끝내 못 붙어도 예외가 밖으로 나가면 안 된다.
     * 나가면 인스턴스가 ready 를 못 받고 ASG 가 교체를 반복한다.
     */
    @Test
    void 끝내_못_붙어도_기동을_막지_않는다() {
        when(redis.hasKey(anyString()))
                .thenThrow(new QueryTimeoutException("계속 실패"));

        assertThat(catchThrown(() -> runner(enabled(1)).run(new DefaultApplicationArguments())))
                .as("기동을 막으면 안 된다")
                .isNull();
    }

    private static Throwable catchThrown(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
