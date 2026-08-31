package com.freshmarket.member.domain;

import com.freshmarket.member.domain.service.KakaoUnlinkRetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * kakao_unlink_failure에 쌓인 WITHDRAWN_FAILED 회원의 unlink를 매일 03시에 재시도한다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling 만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class KakaoUnlinkRetryScheduler {

    private final KakaoUnlinkRetryService kakaoUnlinkRetryService;

    /** 단일 batch 인스턴스에서만 실행한다. */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void retryPendingUnlinks() {
        kakaoUnlinkRetryService.retryAllPending();
    }
}
