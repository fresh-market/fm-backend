package com.freshmarket.coupon.internal.redis;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponRepository;

import io.lettuce.core.event.connection.ConnectionActivatedEvent;
import io.lettuce.core.event.connection.DisconnectedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 재연결을 신호로 삼는 자리의 계약이다.
 *
 * <p>Lettuce 버스를 실제로 띄우지 않는다. 이 클래스가 정하는 것은 "어떤 이벤트를 신호로 보나"
 * 하나이고, 버스 배선은 스프링이 맡는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponSeqReconnectWatcherTest {

    private static final long COUPON_ID = 9001L;

    @Mock
    private LettuceConnectionFactory connectionFactory;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private CouponSeqRebuildTrigger trigger;

    private CouponSeqReconnectWatcher 준비(Duration 최소간격) {
        return new CouponSeqReconnectWatcher(connectionFactory, couponRepository, trigger,
                설정(true, 최소간격), new SimpleMeterRegistry());
    }

    private void 이벤트(CouponSeqReconnectWatcher sut, Object event) {
        ReflectionTestUtils.invokeMethod(sut, "onEvent", event);
    }

    @Test
    @DisplayName("처음 붙는 것은 신호가 아니다")
    void 기동_때의_활성화는_무시한다() {
        CouponSeqReconnectWatcher sut = 준비(Duration.ZERO);

        이벤트(sut, activated());

        verifyNoInteractions(couponRepository);
        verify(trigger, never()).suspect(anyLong());
    }

    @Test
    @DisplayName("끊긴 것을 본 뒤의 활성화가 신호다")
    void 재연결이면_열린_이벤트를_띄운다() {
        when(couponRepository.findOpenLimitedEvents()).thenReturn(List.of(COUPON_ID));
        CouponSeqReconnectWatcher sut = 준비(Duration.ZERO);

        이벤트(sut, activated());          // 기동
        이벤트(sut, disconnected());
        이벤트(sut, activated());

        verify(trigger).suspect(COUPON_ID);
    }

    @Test
    @DisplayName("한 번의 단절에 활성화가 여럿 와도 한 번만 띄운다")
    void 최소_간격_안에서는_한_번만() {
        when(couponRepository.findOpenLimitedEvents()).thenReturn(List.of(COUPON_ID));
        CouponSeqReconnectWatcher sut = 준비(Duration.ofMinutes(1));

        이벤트(sut, activated());          // 기동
        이벤트(sut, disconnected());
        이벤트(sut, activated());
        이벤트(sut, disconnected());
        이벤트(sut, activated());

        verify(trigger).suspect(COUPON_ID);
    }

    @Test
    @DisplayName("DB 를 못 읽으면 조용히 넘긴다")
    void 조회가_실패하면_안_띄운다() {
        when(couponRepository.findOpenLimitedEvents()).thenThrow(new IllegalStateException("DB"));
        CouponSeqReconnectWatcher sut = 준비(Duration.ZERO);

        이벤트(sut, activated());          // 기동
        이벤트(sut, disconnected());
        이벤트(sut, activated());

        verify(trigger, never()).suspect(anyLong());
    }

    @Test
    @DisplayName("열린 이벤트가 없으면 아무것도 안 한다")
    void 대상이_없으면_안_띄운다() {
        when(couponRepository.findOpenLimitedEvents()).thenReturn(List.of());
        CouponSeqReconnectWatcher sut = 준비(Duration.ZERO);

        이벤트(sut, activated());          // 기동
        이벤트(sut, disconnected());
        이벤트(sut, activated());

        verify(trigger, never()).suspect(anyLong());
    }

    @Test
    @DisplayName("기동 때 첫 연결이 실패한 것은 재연결이 아니다")
    void 기동_실패_뒤의_활성화는_무시한다() {
        CouponSeqReconnectWatcher sut = 준비(Duration.ZERO);

        // 붙는 데 실패하면 Lettuce 가 끊김을 먼저 내고 다음 시도에서 활성화를 낸다
        이벤트(sut, disconnected());
        이벤트(sut, activated());

        verifyNoInteractions(couponRepository);
        verify(trigger, never()).suspect(anyLong());
    }

    @Test
    @DisplayName("기동에 실패했어도 그 뒤의 재연결은 센다")
    void 기동_실패_뒤에도_재연결은_띄운다() {
        when(couponRepository.findOpenLimitedEvents()).thenReturn(List.of(COUPON_ID));
        CouponSeqReconnectWatcher sut = 준비(Duration.ZERO);

        이벤트(sut, disconnected());
        이벤트(sut, activated());          // 기동. 여기서는 안 띄운다
        이벤트(sut, disconnected());
        이벤트(sut, activated());          // 진짜 재연결

        verify(trigger).suspect(COUPON_ID);
    }

    private static final SocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 50000);
    private static final SocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 6379);

    private static ConnectionActivatedEvent activated() {
        return new ConnectionActivatedEvent(LOCAL, REMOTE);
    }

    private static DisconnectedEvent disconnected() {
        return new DisconnectedEvent(LOCAL, REMOTE);
    }

    private static CouponIssueProperties 설정(boolean 켬, Duration 최소간격) {
        return new CouponIssueProperties(Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1,
                Integer.MAX_VALUE, Duration.ofSeconds(2), Duration.ofSeconds(3),
                켬, 최소간격, Duration.ofSeconds(5));
    }
}
