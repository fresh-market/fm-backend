package com.freshmarket.coupon.internal.issue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;

/*
 * 플러셔가 웹 계층보다 뒤에 멈추는지 본다.
 *
 * 스프링은 phase 가 높은 것부터 멈추고 SmartLifecycle 기본값이 Integer.MAX_VALUE 라,
 * 재정의하지 않으면 이 빈이 제일 먼저 죽는다. 그러면 그 뒤에 처리되는 요청이 Redis 순번을
 * 받아 아무도 안 읽는 큐에 넣고, 그 순번은 발급되지 않은 채 재고에서 빠진다.
 */
class CouponIssueFlusherPhaseTest {

    /*
     * 웹 서버를 실제로 닫는 WebServerStartStopLifecycle 의 phase 다.
     *
     * 그 클래스가 package-private 이라 상수를 참조할 수 없어 값을 적는다. 아래 시험이
     * 공개된 우아한 종료 상수와의 관계를 함께 보므로, 스프링이 이 배치를 바꾸면 그쪽이 먼저 깨진다.
     */
    private static final int WEB_SERVER_STOP_PHASE = SmartLifecycle.DEFAULT_PHASE - 2048;

    private final int phase = flusher().getPhase();

    /*
     * 진행 중 요청을 기다리는 단계다. 이것만 통과하면 부족하다.
     * 그 뒤에 웹 서버를 닫는 단계가 하나 더 있다.
     */
    @Test
    @SuppressWarnings("removal")
    void 우아한_종료보다_뒤에_멈춘다() {
        assertThat(phase).isLessThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
    }

    /*
     * 여기가 실제 요구다. 같은 phase 면 함께 멈추고 그 안에서는 순서가 보장되지 않아,
     * 웹 서버가 아직 요청을 넘기는 동안 플러셔가 멈출 수 있다.
     */
    @Test
    void 웹_서버가_닫힌_뒤에_멈춘다() {
        assertThat(phase).isLessThan(WEB_SERVER_STOP_PHASE);
    }

    @Test
    void 기본값을_쓰지_않는다() {
        assertThat(phase).isNotEqualTo(SmartLifecycle.DEFAULT_PHASE);
    }

    /*
     * getPhase 는 협력자를 하나도 안 쓴다. 전부 null 로 세워도 된다.
     * mock 을 쓰면 getPhase 가 0 을 돌려줘 실제 구현을 못 본다.
     */
    private CouponIssueFlusher flusher() {
        return new CouponIssueFlusher(null, null, null, null, null, null);
    }
}
