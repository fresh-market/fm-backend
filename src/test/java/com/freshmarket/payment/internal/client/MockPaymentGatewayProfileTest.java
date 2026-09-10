package com.freshmarket.payment.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MockPaymentGatewayProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(MockPaymentGateway.class);

    @Test
    void local_프로필에서만_Mock_PG가_등록된다() {
        runner.withPropertyValues("spring.profiles.active=local").run(context ->
                assertThat(context).hasSingleBean(MockPaymentGateway.class));
    }

    @Test
    void local_프로필이_아니면_Mock_PG가_등록되지_않는다() {
        runner.withPropertyValues("spring.profiles.active=toss-test").run(context ->
                assertThat(context).doesNotHaveBean(MockPaymentGateway.class));
    }
}
