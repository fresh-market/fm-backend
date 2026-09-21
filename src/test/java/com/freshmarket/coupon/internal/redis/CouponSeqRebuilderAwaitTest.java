package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.freshmarket.coupon.internal.entity.Coupon;
import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponSeqRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

/*
 * 주도자가 남의 큐를 언제까지 기다리는지를 본다.
 *
 * 예전에는 고정으로 잤고 그 시간이 재건 정지의 대부분이었다. 지금은 명부의 수만큼 표시가 차면
 * 그 자리에서 끝낸다. 이 클래스가 지켜야 하는 것은 둘이다. 다 모이면 일찍 끝내는가, 그리고
 * 모르겠을 때 일찍 끝내지 않는가. 둘째가 더 중요하다. 덜 기다리면 남의 번호를 다시 내준다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponSeqRebuilderAwaitTest {

    private static final long COUPON_ID = 9001L;
    private static final Duration 기다림 = Duration.ofSeconds(2);

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private CouponRepository couponRepository;
    @Mock
    private MemberCouponSeqRepository seqRepository;
    @Mock
    private CouponSeqInitializer seqInitializer;
    @Mock
    private CouponSeqContributor contributor;
    @Mock
    private CouponSeqInstances instances;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private CouponSeqRebuilder sut;

    @BeforeEach
    void 준비() {
        sut = new CouponSeqRebuilder(redisTemplate, couponRepository, seqRepository,
                seqInitializer, contributor, instances, 설정(기다림));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(hashOperations.entries(anyString())).thenReturn(java.util.Map.of());
        when(seqRepository.findIssuedSeqs(anyLong())).thenReturn(List.of());
        // 목을 먼저 다 만들고 나서 스텁한다. when 인자 안에서 다른 목을 스텁하면 Mockito 가 거부한다
        Coupon coupon = 선착순_쿠폰();
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
    }

    // 다 모이면 정해진 시간을 다 안 쓴다. 이 변경의 목적이다
    @Test
    void 명부만큼_모이면_일찍_끝낸다() {
        when(instances.live()).thenReturn(3);
        when(setOperations.size("coupon:9001:rebuild:done")).thenReturn(3L);

        long 걸린시간 = 재_보기();

        assertThat(걸린시간).isLessThan(기다림.toMillis());
    }

    /*
     * 이 시험이 이 클래스의 핵심이다.
     * 한 대가 아직 안 올렸으면 끝까지 기다려야 한다. 덜 기다리면 그 인스턴스가 쥔 번호를
     * 재건이 "아무도 안 쥔 번호" 로 보고 남에게 다시 내준다.
     */
    @Test
    void 한_대가_안_올렸으면_끝까지_기다린다() {
        when(instances.live()).thenReturn(3);
        when(setOperations.size("coupon:9001:rebuild:done")).thenReturn(2L);

        long 걸린시간 = 재_보기();

        assertThat(걸린시간).isGreaterThanOrEqualTo(기다림.toMillis());
    }

    /*
     * 명부를 못 읽었을 때 "다 모였다" 로 읽으면 아무도 안 기다린 채 키가 선다.
     * 모르는 채로 일찍 끝내는 것이 이 기능에서 가장 나쁜 결과다.
     */
    @Test
    void 명부를_못_읽으면_일찍_안_끝낸다() {
        when(instances.live()).thenReturn(0);
        when(setOperations.size("coupon:9001:rebuild:done")).thenReturn(5L);

        long 걸린시간 = 재_보기();

        assertThat(걸린시간).isGreaterThanOrEqualTo(기다림.toMillis());
    }

    // 표시가 명부보다 많아도 끝낸다. 명부에서 막 빠진 인스턴스가 남긴 것일 수 있다
    @Test
    void 표시가_명부보다_많아도_끝낸다() {
        when(instances.live()).thenReturn(2);
        when(setOperations.size("coupon:9001:rebuild:done")).thenReturn(3L);

        assertThat(재_보기()).isLessThan(기다림.toMillis());
    }

    private long 재_보기() {
        long 시작 = System.nanoTime();
        sut.rebuildIfLost(COUPON_ID);
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - 시작);
    }

    private static Coupon 선착순_쿠폰() {
        Coupon coupon = org.mockito.Mockito.mock(Coupon.class);
        when(coupon.isActive()).thenReturn(true);
        when(coupon.isLimited()).thenReturn(true);
        when(coupon.getIssueEndAt()).thenReturn(LocalDateTime.now().plusDays(1));
        return coupon;
    }

    private static CouponIssueProperties 설정(Duration 기다림) {
        return new CouponIssueProperties(
                Duration.ofSeconds(60),
                Duration.ofMillis(20),
                500,
                1,
                Integer.MAX_VALUE,
                Duration.ofSeconds(2),
                기다림,
                Duration.ofSeconds(5));
    }
}
