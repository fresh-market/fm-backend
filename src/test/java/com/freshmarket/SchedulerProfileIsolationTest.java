package com.freshmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.freshmarket.config.SchedulingConfig;
import com.freshmarket.member.domain.KakaoUnlinkRetryScheduler;
import com.freshmarket.member.domain.service.KakaoUnlinkRetryService;
import com.freshmarket.product.domain.batch.OptionAvailabilitySyncRetryService;
import com.freshmarket.product.domain.batch.OptionAvailabilitySyncScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/*
 * 스케줄러가 batch 프로필에서만 켜지는지 고정한다 (INF-1-10).
 *
 * 운영에서 앱 ASG 는 최대 2대까지 뜨고 배치 전용 인스턴스만 prod,batch 로 뜬다.
 * 분산 락이 없어 프로필이 유일한 방어선이라, 이 규칙이 깨지면 같은 행을 여러 대가 동시에 집는다.
 *
 * 도메인에 속하지 않는 검사라 도메인 패키지가 아니라 베이스 패키지에 둔다.
 */
class SchedulerProfileIsolationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(KakaoUnlinkRetryService.class, () -> mock(KakaoUnlinkRetryService.class))
            .withUserConfiguration(SchedulingConfig.class, KakaoUnlinkRetryScheduler.class);

    @Test
    void batch_프로필이_없으면_스케줄러가_꺼진다() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).doesNotHaveBean(KakaoUnlinkRetryScheduler.class);
        });
    }

    @Test
    void batch_프로필이면_스케줄러가_켜진다() {
        runner.withPropertyValues("spring.profiles.active=batch").run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).hasSingleBean(KakaoUnlinkRetryScheduler.class);
        });
    }

    private final ApplicationContextRunner optionAvailabilitySyncRunner = new ApplicationContextRunner()
            .withBean(OptionAvailabilitySyncRetryService.class, () -> mock(OptionAvailabilitySyncRetryService.class))
            .withUserConfiguration(SchedulingConfig.class, OptionAvailabilitySyncScheduler.class);

    @Test
    void batch_프로필이_없으면_옵션_가용성_재동기화_스케줄러가_꺼진다() {
        optionAvailabilitySyncRunner.run(context -> {
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).doesNotHaveBean(OptionAvailabilitySyncScheduler.class);
        });
    }

    @Test
    void batch_프로필이면_옵션_가용성_재동기화_스케줄러가_켜진다() {
        optionAvailabilitySyncRunner.withPropertyValues("spring.profiles.active=batch").run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).hasSingleBean(OptionAvailabilitySyncScheduler.class);
        });
    }
}
