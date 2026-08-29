package com.freshmarket.stock.domain;

import com.freshmarket.stock.domain.service.AdminLotService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 소비기한이 지난 로트를 매일 자동으로 만료 처리한다(요구사항명세서 "만료 로트 처리"의 배치 실행).
 * 스케줄러 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다 —
 * KakaoUnlinkRetryScheduler와 같은 이유. 실행/소요시간 로그는 SchedulerLoggingAspect가 자동으로 남긴다.
 *
 * 폐기 등록 연계는 여기서 하지 않는다 — DB가 DISPOSE 이력에 admin_id를 강제해서(chk_movement_disposal)
 * 관리자 세션이 없는 배치가 자동으로 폐기까지 끝낼 수 없다. 이 배치는 EXPIRED 전환까지만 하고,
 * 실제 폐기는 별도의 관리자 수동 API(:dispose, 아직 미구현)가 맡는다.
 *
 * (INF-1-01) 배치 유형: 멱등 전이형(idempotent transition). 대상 조회 조건이 status=AVAILABLE라
 * 한 번 EXPIRED로 전환된 행은 다음 실행에서 자연히 대상에서 빠진다 — 같은 날 여러 번 돌거나 재시도로
 * 다시 실행돼도 중복 전환/중복 이력이 안 생긴다. 그래서 재시도·수동 재실행이 항상 안전하다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class AdminLotExpireScheduler {

    private final AdminLotService adminLotService;

    // 매일 새벽 3시(KST)에 실행한다. 명세서에 특정 시각 지정이 없어, 업무 시간 시작 전이면서
    // 자정 직후 트래픽이 몰리는 시간대는 피해 임의로 정했다 — 다른 배치와 겹치면 조정이 필요하다.
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void expireOverdueLots() {
        adminLotService.expireLots();
    }
}
