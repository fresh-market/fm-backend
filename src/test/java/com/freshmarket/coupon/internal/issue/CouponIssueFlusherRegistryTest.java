package com.freshmarket.coupon.internal.issue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/*
 * 플러시가 큐를 쥐고 있다는 것을 언제 알리는지 본다.
 *
 * 명부가 뜻하는 것은 "큐를 쥔 대" 이고, 재건이 그 수만큼 기여를 기다린다. 알리는 자리가 배치를
 * 집을 때 하나뿐이던 때는 큐를 쥐고도 조용한 대가 명부에서 빠졌다. 2026-10-12 S-3 에서 재건이
 * 그 빈 명부를 보고 8밀리초에 끝냈고 둘째 대의 기여가 688밀리초 뒤에 와서 버려졌다.
 */
class CouponIssueFlusherRegistryTest {

    private final CouponSeqRebuildSignal signal = mock(CouponSeqRebuildSignal.class);
    private CouponIssueFlusher sut;

    @AfterEach
    void 정리() {
        if (sut != null) {
            sut.stop();
        }
    }

    /*
     * 이 시험이 이 클래스의 핵심이다.
     * 집을 배치가 없어도 큐에 티켓이 남아 있으면 알려야 한다.
     */
    @Test
    void 집을_배치가_없어도_큐가_있으면_알린다() {
        CouponIssueQueue queue = mock(CouponIssueQueue.class);
        when(queue.size()).thenReturn(3);

        sut = 플러시(queue);
        sut.start();

        verify(signal, timeout(3_000).atLeastOnce()).holdingQueue();
    }

    // 큐가 비면 알리지 않는다. 그래야 명부의 0 이 "정말 아무도 안 쥐었다" 를 뜻한다
    @Test
    void 큐가_비면_알리지_않는다() throws Exception {
        CouponIssueQueue queue = mock(CouponIssueQueue.class);
        when(queue.size()).thenReturn(0);

        sut = 플러시(queue);
        sut.start();
        Thread.sleep(300);

        verify(signal, never()).holdingQueue();
    }

    /*
     * 재건에 멈춰 있는 동안에도 알린다.
     * 재건이 플러시를 멈추므로, 멈춘 채로 조용하면 재건이 스스로 비운 명부를 보게 된다.
     */
    @Test
    void 멈춰_있어도_큐가_있으면_알린다() {
        CouponIssueQueue queue = mock(CouponIssueQueue.class);
        when(queue.size()).thenReturn(5);

        sut = 플러시(queue);
        sut.start();
        sut.pause(Duration.ofSeconds(2));

        verify(signal, timeout(3_000).atLeastOnce()).holdingQueue();
    }

    private CouponIssueFlusher 플러시(CouponIssueQueue queue) {
        /*
         * poll 이 바로 null 을 주게 둔다. 그러면 루프가 "집을 배치가 없다" 경로를 돈다.
         * 배치를 집는 경로는 쓰기까지 가므로 이 시험의 관심이 아니다.
         */
        try {
            when(queue.poll(anyLong())).thenReturn(null);
            when(queue.drainTo(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(0);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }

        @SuppressWarnings("unchecked")
        ObjectProvider<CouponSeqRebuildSignal> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(signal);

        return new CouponIssueFlusher(queue, null, null, 설정(), null, provider, new SimpleMeterRegistry());
    }

    private static CouponIssueProperties 설정() {
        return new CouponIssueProperties(
                Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1, 10_000,
                Duration.ofSeconds(2), Duration.ofSeconds(3), true,
                Duration.ofSeconds(10), Duration.ofSeconds(5));
    }
}
