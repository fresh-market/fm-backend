package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/*
 * 명부를 본다. 재건 주도자가 몇 대를 기다릴지 정하는 근거다.
 *
 * 이 클래스의 실수는 한쪽으로만 위험하다. 살아 있는 수를 실제보다 적게 보면 주도자가 덜
 * 기다리고, 그러면 빠진 인스턴스가 쥔 번호를 재건이 남에게 다시 내준다. 많게 보는 쪽은
 * 그저 조금 더 기다릴 뿐이다. 그래서 모르면 0 을 주고 부르는 쪽이 안 줄이게 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponSeqInstancesTest {

    private static final String KEY = "coupon:instances";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private CouponSeqInstances sut;

    @BeforeEach
    void 준비() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        sut = new CouponSeqInstances(redisTemplate);
    }

    // 인스턴스마다 다른 이름이어야 한다. 같으면 명부가 늘 한 대로 보인다
    @Test
    void 식별자가_인스턴스마다_다르다() {
        CouponSeqInstances 다른_인스턴스 = new CouponSeqInstances(redisTemplate);

        assertThat(sut.id()).isNotBlank().isNotEqualTo(다른_인스턴스.id());
    }

    /*
     * 플러시가 배치마다 부르므로 그대로 쓰면 초당 오십 번이다.
     * 명부는 살아 있나만 보는 것이라 그렇게 자주 쓸 이유가 없다.
     */
    @Test
    void 연달아_불러도_한_번만_쓴다() {
        sut.refresh();
        sut.refresh();
        sut.refresh();

        verify(zSetOperations, times(1)).add(eq(KEY), eq(sut.id()), anyDouble());
    }

    /*
     * 식별자를 JVM 마다 새로 만들므로 배포와 교체마다 항목이 는다.
     * 치우지 않으면 명부가 끝없이 자라는데, live 가 구간만 세어 답은 계속 맞아 안 드러난다.
     */
    @Test
    void 갱신하면서_오래된_항목을_치운다() {
        sut.refresh();

        verify(zSetOperations).removeRangeByScore(eq(KEY), eq(0d), anyDouble());
    }

    // 치우는 경계가 살아 있다고 보는 구간을 침범하면 안 된다
    @Test
    void 치우는_경계가_살아있는_구간보다_오래됐다() {
        sut.refresh();
        org.mockito.ArgumentCaptor<Double> 치운_경계 = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(zSetOperations).removeRangeByScore(eq(KEY), eq(0d), 치운_경계.capture());

        when(zSetOperations.count(eq(KEY), anyDouble(), anyDouble())).thenReturn(1L);
        sut.live();
        org.mockito.ArgumentCaptor<Double> 살아있는_경계 = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(zSetOperations).count(eq(KEY), 살아있는_경계.capture(), anyDouble());

        assertThat(치운_경계.getValue()).isLessThan(살아있는_경계.getValue());
    }

    /*
     * 이 시험이 이 클래스의 핵심이다.
     * 못 세었을 때 큰 수를 주면 주도자가 영영 안 끝내고, 실제 수를 아는 척하면 덜 기다린다.
     * 0 을 주어야 부르는 쪽이 "모른다" 로 읽고 조기 종료를 포기한다.
     */
    @Test
    void 못_세면_0_을_준다() {
        when(zSetOperations.count(anyString(), anyDouble(), anyDouble()))
                .thenThrow(new RedisConnectionFailureException("Redis 가 답하지 않는다"));

        assertThat(sut.live()).isZero();
    }

    // 갱신이 실패해도 부른 쪽은 방금 배치를 성공으로 끝낸 플러시 스레드다
    @Test
    void 갱신이_실패해도_안_터진다() {
        when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                .thenThrow(new RedisConnectionFailureException("Redis 가 답하지 않는다"));

        assertThatCode(() -> sut.refresh()).doesNotThrowAnyException();
    }
}
