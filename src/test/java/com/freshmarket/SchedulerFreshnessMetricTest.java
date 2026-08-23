package com.freshmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.freshmarket.common.logging.SchedulerLoggingAspect;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/*
 * 배치 신선도 지표를 고정한다.
 *
 * 배치는 틀리게 도는 것보다 조용히 안 도는 것이 위험하다. 배치 인스턴스는 ALB 대상이 아니라
 * HealthyHostCount 에도 안 걸리므로, 이 지표가 유일한 감시 경로다 (BatchNotRun 알람).
 *
 * 도메인에 속하지 않는 검사라 도메인 패키지가 아니라 베이스 패키지에 둔다.
 */
class SchedulerFreshnessMetricTest {

    private static final String JOB = "SomeScheduler.run()";

    /*
     * 상수를 참조하지 않고 문자열을 그대로 적는다.
     * Prometheus 의 BatchNotRun 알람이 batch_job_last_success_timestamp_seconds 라는
     * 이 이름에 의존하므로, 이름이 바뀌면 알람이 조용히 죽는다. 여기서 잡는다.
     * 라벨 이름도 마찬가지다. job 은 Prometheus 가 스크랩 잡 이름으로 쓰는 예약 라벨이라 못 쓴다.
     */
    private static final String LAST_SUCCESS = "batch.job.last.success.timestamp";

    private SimpleMeterRegistry registry;
    private SchedulerLoggingAspect aspect;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        aspect = new SchedulerLoggingAspect(registry);
    }

    private static ProceedingJoinPoint joinPoint(Object result, Throwable failure) throws Throwable {
        Signature signature = mock(Signature.class);
        when(signature.toShortString()).thenReturn(JOB);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        if (failure == null) {
            when(joinPoint.proceed()).thenReturn(result);
        } else {
            when(joinPoint.proceed()).thenThrow(failure);
        }
        return joinPoint;
    }

    private Double gauge() {
        return registry.find(LAST_SUCCESS).tag("scheduler", JOB).gauge().value();
    }

    @Test
    void 성공하면_마지막_성공_시각이_올라간다() throws Throwable {
        long before = Instant.now().getEpochSecond();

        aspect.logScheduledExecution(joinPoint("ok", null));

        assertThat(gauge()).isGreaterThanOrEqualTo(before);
    }

    /*
     * 실패만 반복하는 작업을 잡기 위한 것이다.
     * 첫 실행 시각으로 초기화되고 성공할 때만 갱신되므로, 계속 실패하면 값이 낡아 알람이 울린다.
     */
    @Test
    void 실패하면_마지막_성공_시각이_그대로다() throws Throwable {
        aspect.logScheduledExecution(joinPoint("ok", null));
        double 성공_직후 = gauge();

        assertThatThrownBy(() -> aspect.logScheduledExecution(joinPoint(null, new IllegalStateException("실패"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(gauge()).isEqualTo(성공_직후);
    }

    /*
     * 0 으로 두면 기동 직후부터 낡은 것으로 보여 첫 실행 전에 알람이 울린다.
     */
    @Test
    void 한번도_성공하지_못해도_0_이_아니다() throws Throwable {
        long before = Instant.now().getEpochSecond();

        assertThatThrownBy(() -> aspect.logScheduledExecution(joinPoint(null, new IllegalStateException("실패"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(gauge()).isGreaterThanOrEqualTo(before);
    }
}
