package com.freshmarket.payment.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/*
 * 기동하는 프로필마다 PaymentGateway 가 정확히 하나 등록되는지 고정한다.
 *
 * #203 은 MockPaymentGateway 를 local 로 좁히면서 prod 에 PaymentGateway 가 0개가 되어 기동에 실패했다.
 * 그때 이 테스트는 "local 이 아니면 Mock 이 없다" 만 보고 있어서 그 상태를 정상으로 통과시켰다.
 * 그래서 특정 구현체의 유무가 아니라 "프로필마다 게이트웨이가 하나" 를 본다.
 *
 * 실제 PG 구현체를 추가하면 withUserConfiguration 에 그 클래스를 함께 올린다.
 * 그래야 prod 에서 Mock 과 실제 구현체가 둘 다 뜨거나 둘 다 빠지는 경우를 이 테스트가 잡는다.
 */
class MockPaymentGatewayProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(MockPaymentGateway.class);

    // prod,batch 는 배치 인스턴스다. PaymentReconciliationService 가 여기서도 PaymentGateway 를 주입받는다.
    @ParameterizedTest
    @ValueSource(strings = {"local", "prod", "prod,batch"})
    void 기동하는_프로필마다_PaymentGateway가_정확히_하나_등록된다(String profiles) {
        runner.withPropertyValues("spring.profiles.active=" + profiles).run(context ->
                assertThat(context).hasSingleBean(PaymentGateway.class));
    }

    // integrationTest 는 FakePaymentGatewayIntegrationTest 가 유일한 PaymentGateway 여야 한다.
    @Test
    void integrationTest_프로필에서는_Mock_PG가_등록되지_않는다() {
        runner.withPropertyValues("spring.profiles.active=integrationTest").run(context ->
                assertThat(context).doesNotHaveBean(PaymentGateway.class));
    }
}
