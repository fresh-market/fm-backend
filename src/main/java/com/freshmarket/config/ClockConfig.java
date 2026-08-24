package com.freshmarket.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * 시간에 의존하는 로직(JwtTokenProvider의 발급/만료 시각 등)이 System.currentTimeMillis()를
 * 직접 부르지 않고 이 Clock을 주입받게 하기 위한 빈 등록. 테스트는 Clock.fixed(...)로 "지금"을
 * 원하는 시점에 고정해서 만료 전/후 같은 시간 경계 케이스를 결정적으로 재현할 수 있다.
 *
 * systemDefaultZone()을 쓰는 이유는 프로젝트 전반이 LocalDateTime.now()/ZoneId.systemDefault()로
 * 시스템 기본 시간대를 그대로 쓰고 있어서(BaseMutableTimeEntity 등), 이 빈만 UTC 등으로 다르게
 * 잡으면 시간대가 갈리는 혼란이 생긴다 — 기존 동작과 동일하게 맞춘다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
