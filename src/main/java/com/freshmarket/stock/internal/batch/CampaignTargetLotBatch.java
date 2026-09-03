package com.freshmarket.stock.internal.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.freshmarket.stock.internal.ExpiringSoonPolicy;

/*
 * 캠페인 대상 로트 확정을 매일 자정에 건다.
 *
 * 확정 로직은 CampaignTargetLotRebuildService 가 갖고 이 클래스는 시각만 정한다.
 * 둘을 나눈 이유가 있다 — 같은 일을 관리자 재실행 API 도 불러야 하는데, 그쪽은 API
 * 인스턴스에서 들어오므로 batch 프로필에 묶인 빈을 주입받을 수 없다.
 * (CouponConsistencyScheduler / OptionAvailabilitySyncScheduler 와 같은 구조다.)
 *
 * batch 프로필에서만 뜬다 (INF-1-10, ArchitectureTest 로 강제됨). 분산 락이 없어
 * 프로필이 유일한 방어선이라, 이게 빠지면 앱 서버 여러 대가 같은 로트를 동시에 집는다.
 *
 * zone 을 명시한다. 이 기능의 "자정" 은 호스트가 아니라 한국 자정이고, 기준일을 세는
 * ExpiringSoonPolicy.businessToday() 도 같은 시간대를 본다. 둘이 갈리면 배치가 확정한
 * 기준일과 조회가 찾는 기준일이 하루 어긋난다.
 *
 * 실행/소요시간 로그는 SchedulerLoggingAspect 가 @Scheduled 메서드마다 자동으로 남긴다.
 */
@Component
@Profile("batch")
@RequiredArgsConstructor
public class CampaignTargetLotBatch {

    private final CampaignTargetLotRebuildService campaignTargetLotRebuildService;

    @Scheduled(cron = "0 0 0 * * *", zone = ExpiringSoonPolicy.BUSINESS_ZONE_ID)
    public void run() {
        campaignTargetLotRebuildService.rebuild();
    }
}
