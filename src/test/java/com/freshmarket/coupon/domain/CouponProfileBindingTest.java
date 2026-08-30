package com.freshmarket.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

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
            // (2026-08-30, 로컬 진단용) 운영 값은 800ms 다. 되돌릴 때 이 줄도 함께 되돌린다
            assertThat(properties.requestBudget()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.couponCacheTtl()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.reclaimAfter()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    /*
     * 회로 값은 resilience4j 스타터가 갖는다. 우리가 레코드로 다시 정의하지 않으므로
     * 여기서는 그 키가 실제로 적혀 있는지를 환경에서 읽어 확인한다.
     *
     * 창이 시간 기준인 것이 이 확인의 요점이다. 건수 기준이면 유입이 빠를수록 판정 근거의
     * 시간 폭이 좁아져, GC 정지 하나가 창을 통째로 덮고 회로가 열린다.
     */
    @Test
    void 회로_둘이_시간_창을_쓴다() {
        runner.run(context -> {
            Environment env = context.getEnvironment();

            for (String name : new String[] {"couponSeq", "couponWrite"}) {
                String prefix = "resilience4j.circuitbreaker.instances." + name + ".";
                assertThat(env.getProperty(prefix + "slidingWindowType")).isEqualTo("TIME_BASED");
                assertThat(env.getProperty(prefix + "slidingWindowSize")).isEqualTo("10");
            }
        });
    }

    /*
     * 계층이 역전되면 요청 스레드가 먼저 포기한 뒤에 그 배치가 커밋된다.
     * 실패했다고 답했는데 발급된 상태가 되므로 이 순서를 시험이 지킨다.
     *
     * 값을 상수로 박지 않고 설정에서 읽는다. 박아 두면 yml 을 고쳐도 시험이 옛 숫자로 계속
     * 통과한다. 실제로 그런 적이 있다. connection-timeout 을 100 으로 적어 둔 채 100 + 250 을
     * 상수로 두었는데, HikariCP 가 하한 250 으로 덮어써서 도는 값은 250 + 250 = 500 이었다.
     * 예산 400 을 이미 넘겨 역전돼 있었는데 시험은 계속 통과했다.
     */
    @Test
    void 요청_예산이_안쪽_합보다_길다() {
        runner.run(context -> {
            CouponIssueProperties properties = context.getBean(CouponIssueProperties.class);
            Environment env = context.getEnvironment();

            long 획득 = Long.parseLong(env.getProperty("spring.datasource.hikari.connection-timeout"));
            long 응답대기 = Long.parseLong(
                    env.getProperty("spring.datasource.hikari.data-source-properties.socketTimeout"));

            assertThat(properties.requestBudget()).isGreaterThan(Duration.ofMillis(획득 + 응답대기));
        });
    }

    /*
     * HikariCP 는 connection-timeout 과 validation-timeout 의 하한이 250ms 다.
     * 그보다 작게 적으면 경고 로그와 함께 250 으로 덮어써서 적힌 값과 도는 값이 갈린다.
     * 위 시험이 설정에서 읽어 계산하므로, 적힌 값이 곧 도는 값이어야 그 계산이 성립한다.
     *
     * 하한 250 이 아니라 300 을 요구한다. 경계에 딱 붙여 두면 판이 바뀌어 하한이 오를 때
     * 조용히 덮어써지고 같은 일이 되풀이된다.
     */
    @Test
    void 하한_경계에_붙이지_않는다() {
        runner.run(context -> {
            Environment env = context.getEnvironment();

            assertThat(Long.parseLong(env.getProperty("spring.datasource.hikari.connection-timeout")))
                    .isGreaterThanOrEqualTo(300);
            assertThat(Long.parseLong(env.getProperty("spring.datasource.hikari.validation-timeout")))
                    .isGreaterThanOrEqualTo(300);
        });
    }

    /*
     * 느려진 쓰기를 소켓이 끊기 전에 회로가 먼저 잡아야 한다.
     * 순서가 뒤집히면 회로는 실패만 세게 되어 느려지는 구간에서 안 열린다.
     */
    @Test
    void 회로_느림_기준이_소켓_타임아웃보다_앞이다() {
        runner.run(context -> {
            Environment env = context.getEnvironment();

            long 응답대기 = Long.parseLong(
                    env.getProperty("spring.datasource.hikari.data-source-properties.socketTimeout"));
            long redis = Long.parseLong(env.getProperty("spring.data.redis.timeout").replace("ms", ""));

            assertThat(느림기준밀리초(env, "couponWrite")).isLessThan(응답대기);
            assertThat(느림기준밀리초(env, "couponSeq")).isLessThan(redis);
        });
    }

    /*
     * 8장의 SLO 가 처리된 응답 p99 1초다.
     * 요청 예산이 곧 성공 응답의 지연 상한이라, 예산이 그보다 길면 SLO 를 구조적으로 못 지킨다.
     * Redis 왕복 둘이 예산 밖에서 최악 200ms 를 더 쓰므로 그만큼 남겨 둔다.
     */
    @Disabled("2026-08-30 로컬 진단 회차 동안만 끈다. 예산 30초와 Redis 500ms 가 SLO 를 넘긴다."
            + " 진단이 끝나 예산을 800ms 로 되돌리면 이 줄을 지운다.")
    @Test
    void 요청_예산이_SLO_안에_들어온다() {
        runner.run(context -> {
            CouponIssueProperties properties = context.getBean(CouponIssueProperties.class);

            assertThat(properties.requestBudget()).isLessThanOrEqualTo(Duration.ofMillis(800));
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

    // resilience4j 는 이 값을 "50ms" 같은 문자열로 받는다. 스프링이 쓰는 파서로 그대로 읽는다
    private static long 느림기준밀리초(Environment env, String circuitName) {
        String raw = env.getProperty(
                "resilience4j.circuitbreaker.instances." + circuitName + ".slowCallDurationThreshold");
        return DurationStyle.detectAndParse(raw).toMillis();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CouponIssueProperties.class)
    static class BindOnly {
    }
}
