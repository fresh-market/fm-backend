package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.domain.service.CouponEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 배치가 마감이 지난 선착순 이벤트를 끝낸다.
 * 스케줄러 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다.
 * 실행과 소요 시간 로그는 SchedulerLoggingAspect 가 자동으로 남긴다.
 *
 * 특정 시각에 한 번만 도는 예약과 견줘 이 방식으로 정했다. 예약을 JVM 에 두면 그 인스턴스가
 * 사라질 때 함께 사라지는데, 선착순은 전용 ASG 를 이벤트 뒤에 내리는 구조라 그 소멸이 정상
 * 경로다. 예약을 DB 에 두어 살리면 그 표를 읽는 폴링이 다시 생긴다.
 *
 * (INF-1-01) 배치 유형: 멱등 전이형. 대상 조회 조건이 is_active = TRUE 라 한 번 꺼진 쿠폰은
 * 다음 실행에서 자연히 빠진다. 여러 번 돌거나 재시도로 다시 실행돼도 중복 처리가 없다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling 만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class CouponEventScheduler {

    private final CouponEventService couponEventService;

    /*
     * 이 반복을 서비스가 아니라 여기에 둔 이유가 있다.
     * 쿠폰 하나가 트랜잭션 하나여야 하는데, 서비스가 자기 메서드를 부르면 스프링 프록시를 안
     * 거쳐서 그 경계가 사라진다. 이 어댑터가 부르면 매번 프록시를 지난다.
     *
     * 10분 주기로 충분하다. 마감 조건에 이미 60초 대기가 들어 있어 그보다 자주 돌 이유가 없고,
     * 마감 지난 요청은 CouponIssueService 의 기간 검사가 이미 막는다. 스위치가 늦게 꺼져서
     * 달라지는 것은 그동안 회수가 더 돌 수 있다는 것뿐이라 오히려 순번을 덜 잃는다.
     */
    @Scheduled(fixedDelayString = "PT10M")
    public void closeFinishedEvents() {
        for (Long couponId : couponEventService.findClosableEvents()) {
            couponEventService.closeIfDue(couponId);
        }
    }
}
