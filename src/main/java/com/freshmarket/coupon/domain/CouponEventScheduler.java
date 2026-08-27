package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.domain.service.CouponEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 배치가 마감 시각이 지난 선착순 이벤트를 끄고 치운다.
 * 스케줄러 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다.
 * 실행과 소요 시간 로그는 SchedulerLoggingAspect 가 자동으로 남긴다.
 *
 * 특정 시각에 한 번만 도는 예약과 견줘 이 방식으로 정했다. 예약을 JVM 에 두면 그 인스턴스가
 * 사라질 때 함께 사라지는데, 선착순은 전용 ASG 를 이벤트 뒤에 내리는 구조라 그 소멸이 정상
 * 경로다. 예약을 DB 에 두어 살리면 그 표를 읽는 폴링이 다시 생긴다.
 *
 * 정밀도도 필요 없다. 마감이 지난 요청은 CouponIssueService 의 기간 검사가 이미 막고, Redis
 * 키는 EXPIREAT 이 따로 치운다. 스위치가 늦게 꺼져서 달라지는 것은 정리 대기가 늦게 시작되는
 * 것뿐이다.
 *
 * (INF-1-01) 배치 유형: 멱등 전이형. 끄기는 is_active = TRUE 인 행만 보고, 정리는 발급 수가
 * 실제 행 수와 다른 행만 본다. 그래서 배치가 여러 번 돌거나 재시도로 다시 실행돼도 같은 쿠폰을
 * 두 번 처리하지 않는다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling 만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class CouponEventScheduler {

    private final CouponEventService couponEventService;

    /*
     * 끄기는 자주 돈다. 쿠폰 표만 훑는 갱신이라 사실상 공짜다.
     * 행이 수십 개고 서브쿼리도 없어서, 더 자주 돌아도 부담이 안 생긴다.
     */
    @Scheduled(fixedDelayString = "PT1M")
    public void closeFinishedEvents() {
        couponEventService.closeFinishedEvents();
    }

    /*
     * 정리는 뜸하게 돈다. 후보 쿠폰마다 member_coupon 을 세는 상관 서브쿼리가 있어 끄기보다 무겁다.
     *
     * 방금 끈 쿠폰은 이번 실행에서 안 걸린다. 대상 조건이 "꺼진 지 60초가 지났을 것" 이고,
     * 그 대기가 진행 중인 플러시가 결판나기를 기다리는 시간이다. 그래서 두 주기가 서로를
     * 기다릴 필요가 없다.
     */
    @Scheduled(fixedDelayString = "PT10M")
    public void cleanupClosedEvents() {
        couponEventService.cleanupClosedEvents();
    }
}
