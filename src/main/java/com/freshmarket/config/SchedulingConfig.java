package com.freshmarket.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * 스케줄러는 batch 프로필에서만 켠다 (INF-1-10).
 * 앱 인스턴스에서 켜지면 앱 대수만큼 중복 실행되는데, 분산 락이 없어 아무것도 막지 못한다.
 * 배치 전용 인스턴스만 prod,batch 로 뜨고 앱 ASG 는 prod 로 뜬다.
 */
@Configuration
@Profile("batch")
@EnableScheduling
public class SchedulingConfig {
}
