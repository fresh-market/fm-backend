package com.freshmarket.stock.internal.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.freshmarket.stock.internal.ExpiringSoonPolicy;
import com.freshmarket.stock.internal.exception.StockErrorCode;
import com.freshmarket.stock.internal.exception.StockException;

/*
 * 캠페인 대상 로트 확정을 매일 자정에 건다.
 *
 * 확정 로직은 CampaignTargetLotRebuildService 가 갖고 이 클래스는 시각만 정한다.
 * 둘을 나눈 이유가 있다 — 같은 일을 관리자 재실행 API 도 불러야 하는데, 그쪽은 API
 * 인스턴스에서 들어오므로 batch 프로필에 묶인 빈을 주입받을 수 없다.
 * (CouponConsistencyScheduler / OptionAvailabilitySyncScheduler 와 같은 구조다.)
 *
 * batch 프로필에서만 뜬다 (INF-1-10, ArchitectureTest 로 강제됨). 다만 이 배치에서는
 * 프로필이 유일한 방어선이 아니다 — 확정 자체가 campaign_rebuild_lock 을 잡고 시작하므로
 * 프로필이 빠져 여러 대가 깨어나도 한 대만 확정한다. 다른 스케줄러는 아직 프로필뿐이다.
 *
 * zone 을 명시한다. 이 기능의 "자정" 은 호스트가 아니라 한국 자정이고, 기준일을 세는
 * ExpiringSoonPolicy.businessToday() 도 같은 시간대를 본다. 둘이 갈리면 배치가 확정한
 * 기준일과 조회가 찾는 기준일이 하루 어긋난다.
 *
 * 실행/소요시간 로그는 SchedulerLoggingAspect 가 @Scheduled 메서드마다 자동으로 남긴다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class CampaignTargetLotBatch {

    private final CampaignTargetLotRebuildService campaignTargetLotRebuildService;

    /*
     * 잠금을 못 잡은 것은 실패가 아니다.
     *
     * SchedulerLoggingAspect 는 예외가 올라오면 SCHEDULER_FAILED 를 남기고
     * batch.job.last.success.timestamp 를 갱신하지 않는다. 그 값이 낡는 것으로 알람이 울리므로,
     * 확정을 다른 쪽이 이미 하고 있어 양보한 경우까지 올려 보내면 멀쩡한 날에 알람이 울린다.
     * 이 스케줄의 목적은 "오늘 대상이 확정되어 있게 하는 것" 이고, 남이 하고 있으면 그 목적은
     * 달성된다. 그래서 여기서 삼키고 정상 종료한다.
     *
     * 삼키는 것은 이 한 가지 코드뿐이다. 나머지 실패는 그대로 올려 보내야 알람이 울린다.
     *
     * 이 판단은 스케줄 쪽 사정이라 여기에 둔다. 확정 로직에 넣으면 관리자 재실행 API 도 함께
     * 조용해져서, 버튼을 눌러도 아무 일이 없는 것처럼 보인다 — 그쪽은 409 를 받아야 한다.
     *
     * 남는 구멍이 하나 있다. 이긴 쪽이 그 뒤에 실패하면 아무도 확정하지 않았는데 여기서는
     * 성공으로 셈한다. 이긴 쪽도 자기 자리에서 실패를 남기므로(스케줄이면 SCHEDULER_FAILED,
     * 관리자 재실행이면 500) 신호가 아주 사라지지는 않는다. 겹침이 잦아지면 확정본 존재
     * 여부를 직접 보는 감시로 바꾸는 것이 맞다.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = ExpiringSoonPolicy.BUSINESS_ZONE_ID)
    public void run() {
        try {
            campaignTargetLotRebuildService.rebuild();
        } catch (StockException e) {
            if (e.getErrorCode() != StockErrorCode.CAMPAIGN_REBUILD_IN_PROGRESS) {
                throw e;
            }
            log.warn("event=CAMPAIGN_TARGET_LOT_REBUILD_SKIPPED reason=ALREADY_RUNNING"
                    + " — 다른 쪽이 확정 중이라 양보한다");
        }
    }
}
