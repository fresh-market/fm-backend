package com.freshmarket.common.logging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * {@code @Scheduled} 메서드 실행마다 시작/종료(또는 실패)/소요시간을 자동으로 로깅하고,
 * 마지막으로 성공한 시각을 지표로 내보내는 공통 Aspect.
 * 스케줄러 클래스가 로깅 코드를 따로 안 짜도 되고, {@code @Scheduled}만 붙이면 이 Aspect가 자동으로
 * 적용된다 — "실행은 됐는데 처리할 게 없어서 로그를 아예 안 남기는" 개별 스케줄러가 있어도, 이
 * Aspect 덕분에 "그 시간에 스케줄러가 정상적으로 돌긴 한 건지"는 최소 1줄로 항상 확인할 수 있다.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class SchedulerLoggingAspect {

    private static final String LAST_SUCCESS = "batch.job.last.success.timestamp";

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicLong> lastSuccessByJob = new ConcurrentHashMap<>();

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object logScheduledExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String job = joinPoint.getSignature().toShortString();
        Instant start = Instant.now();
        log.info("event=SCHEDULER_START job={}", job);
        AtomicLong lastSuccess = lastSuccessGauge(job, start);

        try {
            Object result = joinPoint.proceed();
            lastSuccess.set(Instant.now().getEpochSecond());
            log.info("event=SCHEDULER_END job={} durationMs={}",
                    job, Duration.between(start, Instant.now()).toMillis());
            return result;
        } catch (Throwable ex) {
            log.error("event=SCHEDULER_FAILED job={} durationMs={}",
                    job, Duration.between(start, Instant.now()).toMillis(), ex);
            throw ex;
        }
    }

    /*
     * 배치는 틀리게 도는 것보다 조용히 안 도는 것이 위험하다.
     * 에러 알람은 작업이 돌아야 울리므로 프로세스가 아예 안 뜨면 아무 신호도 없다.
     * 그래서 마지막 성공 시각을 내보내고 그 값이 낡는 것으로 감시한다.
     *
     * 첫 실행 시각으로 초기화하는 이유는 계속 실패하는 작업도 잡기 위해서다.
     * 성공할 때만 값이 갱신되므로, 실패만 반복하면 이 값이 그대로 낡아 알람이 울린다.
     * 0 으로 두면 기동 직후부터 낡은 것으로 보여 첫 실행 전에 울린다.
     */
    private AtomicLong lastSuccessGauge(String job, Instant firstSeen) {
        return lastSuccessByJob.computeIfAbsent(job, name -> {
            AtomicLong holder = new AtomicLong(firstSeen.getEpochSecond());
            Gauge.builder(LAST_SUCCESS, holder, AtomicLong::doubleValue)
                    /*
                     * job 이 아니라 scheduler 로 붙인다.
                     * Prometheus 의 job 은 스크랩 잡 이름으로 예약되어 있어, 같은 이름을 쓰면
                     * 수집 때 exported_job 으로 밀려나고 알람이 엉뚱한 값을 가리킨다.
                     */
                    .tag("scheduler", name)
                    .baseUnit("seconds")
                    .description("Unix time of the last successful run")
                    .register(meterRegistry);
            return holder;
        });
    }
}
