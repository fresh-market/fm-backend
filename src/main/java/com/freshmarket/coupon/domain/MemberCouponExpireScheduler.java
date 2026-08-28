package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.domain.service.MemberCouponStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 배치가 유효기간이 지난 발급분을 만료 처리한다.
 * 스케줄러 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다.
 * 실행과 소요 시간 로그는 SchedulerLoggingAspect 가 자동으로 남긴다.
 *
 * (INF-1-01) 배치 유형: 멱등 전이형. 대상 조회 조건이 status = 'ISSUED' 라 한 번 EXPIRED 로
 * 바뀐 행은 다음 실행에서 자연히 빠진다. 여러 번 돌거나 재시도로 다시 실행돼도 중복 전이가 없다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling 만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class MemberCouponExpireScheduler {

    /*
     * 이 상한이 한 실행에서 도는 청크 수를 끊는다.
     * 없으면 대상이 아주 많을 때 이 실행이 안 끝나서 다음 배치가 밀리고, 앱이 내려갈 때 종료
     * 신호도 제때 못 받는다. 여기서 못 다 한 것은 다음 실행이 이어서 가져간다.
     */
    private static final int MAX_CHUNKS_PER_RUN = 1000;

    private final MemberCouponStatusService memberCouponStatusService;

    /*
     * 이 반복을 서비스가 아니라 여기에 둔 이유가 있다.
     * 청크 하나가 트랜잭션 하나여야 하는데, 서비스가 자기 메서드를 부르면 스프링 프록시를 안
     * 거쳐서 그 경계가 사라진다. 이 어댑터가 부르면 매번 프록시를 지난다.
     *
     * 매일 새벽 4시에 돈다. AdminLotExpireScheduler 가 3시라 겹치지 않게 한 시간 뒤에 둔다.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void expireOverdueCoupons() {
        for (int i = 0; i < MAX_CHUNKS_PER_RUN; i++) {
            if (memberCouponStatusService.expireOverdueChunk() == 0) {
                return;
            }
        }
    }
}
