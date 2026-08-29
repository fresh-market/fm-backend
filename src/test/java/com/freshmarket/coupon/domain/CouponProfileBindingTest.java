package com.freshmarket.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/*
 * application-coupon.yml 이 실제로 바인딩되는지 본다.
 *
 * 키 이름을 하나 틀리면 스프링이 조용히 기본값을 쓴다. 그러면 부하 시험에서 값을 바꿔도
 * 아무 일이 안 일어나고, 왜 안 바뀌는지를 한참 찾게 된다. 컨테이너 없이 설정만 읽어 확인한다.
 */
class CouponProfileBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(BindOnly.class)
            .withPropertyValues("spring.profiles.active=coupon");

    @Test
    void 발급_조율_값이_프로파일에서_온다() {
        runner.run(context -> {
            CouponIssueProperties properties = context.getBean(CouponIssueProperties.class);

            assertThat(properties.batchWindow()).isEqualTo(Duration.ofMillis(20));
            assertThat(properties.batchSize()).isEqualTo(500);
            assertThat(properties.flushThreads()).isEqualTo(1);
            assertThat(properties.requestBudget()).isEqualTo(Duration.ofMillis(400));
            assertThat(properties.couponCacheTtl()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.reclaimAfter()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    // 회로가 둘이고 세는 대상이 달라 느림 기준도 다르다
    @Test
    void 회로_값이_둘_다_프로파일에서_온다() {
        runner.run(context -> {
            CouponCircuitProperties properties = context.getBean(CouponCircuitProperties.class);

            assertThat(properties.seq().slowCallDuration()).isEqualTo(Duration.ofMillis(50));
            assertThat(properties.write().slowCallDuration()).isEqualTo(Duration.ofMillis(150));
            assertThat(properties.seq().minimumNumberOfCalls()).isEqualTo(20);
            assertThat(properties.write().waitDurationInOpen()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    /*
     * 계층이 역전되면 요청 스레드가 먼저 포기한 뒤에 그 배치가 커밋된다.
     * 실패했다고 답했는데 발급된 상태가 되므로 이 순서를 시험이 지킨다.
     */
    @Test
    void 요청_예산이_안쪽_합보다_길다() {
        runner.run(context -> {
            CouponIssueProperties properties = context.getBean(CouponIssueProperties.class);
            Duration 안쪽_합 = Duration.ofMillis(100 + 250);   // connection-timeout + socketTimeout

            assertThat(properties.requestBudget()).isGreaterThan(안쪽_합);
        });
    }

    /*
     * 8장의 SLO 가 처리된 응답 p99 500ms 다.
     * 요청 예산이 곧 성공 응답의 지연 상한이라, 예산이 그보다 길면 SLO 를 구조적으로 못 지킨다.
     * Redis 왕복 둘이 예산 밖에서 최악 200ms 를 더 쓰므로 그만큼 남겨 둔다.
     */
    @Test
    void 요청_예산이_SLO_안에_들어온다() {
        runner.run(context -> {
            CouponIssueProperties properties = context.getBean(CouponIssueProperties.class);

            assertThat(properties.requestBudget()).isLessThan(Duration.ofMillis(500));
        });
    }

    // 회수 기준이 요청 예산보다 짧으면 아직 살아 있는 요청의 번호를 뺏는다
    @Test
    void 회수_기준이_요청_예산보다_길다() {
        runner.run(context -> {
            CouponIssueProperties properties = context.getBean(CouponIssueProperties.class);

            assertThat(properties.reclaimAfter()).isGreaterThan(properties.requestBudget());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CouponIssueProperties.class, CouponCircuitProperties.class})
    static class BindOnly {
    }
}
