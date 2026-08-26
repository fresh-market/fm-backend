package com.freshmarket.member.domain.scheduler;

import com.freshmarket.member.domain.service.kakao.KakaoUnlinkStuckReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * (2026-08-24) kakao_unlink_failure에서 재시도 포기 문턱(KakaoUnlinkFailure.shouldGiveUp())을 넘은
 * 행이 몇 개나 쌓여 있는지 하루에 한 번 요약해서 알린다. KakaoUnlinkRetryScheduler(10분)와 별도
 * 클래스로 뺀 이유는 둘의 목적과 그에 맞는 주기가 다르기 때문이다 — 자세한 이유는
 * KakaoUnlinkStuckReportService 클래스 주석 참고. 스케줄러 어댑터라 서비스가 아니고,
 * domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다 — KakaoUnlinkRetryScheduler와 같은
 * 이유. 실행/소요시간 로그는 SchedulerLoggingAspect가 @Scheduled 메서드마다 자동으로 남긴다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling 만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class KakaoUnlinkStuckReportScheduler {

    private static final long FIXED_DELAY_MS = 24 * 60 * 60 * 1000; // 1일

    private final KakaoUnlinkStuckReportService kakaoUnlinkStuckReportService;

    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void reportStuckUnlinks() {
        kakaoUnlinkStuckReportService.reportStuck();
    }
}
