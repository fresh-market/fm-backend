package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.domain.service.CouponConsistencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 배치가 정합성 검증을 하루 한 번 돌린다.
 * 스케줄러 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다.
 * 실행과 소요 시간 로그는 SchedulerLoggingAspect 가 자동으로 남긴다.
 *
 * (INF-1-01) 배치 유형: 읽기 전용 검사형. 아무것도 안 고치므로 여러 번 돌아도 데이터가 안 바뀐다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling 만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class CouponConsistencyScheduler {

    private final CouponConsistencyService couponConsistencyService;

    /*
     * 이 검증을 이벤트 종료 배치에 붙이지 않고 따로 두는 이유가 셋이다.
     *
     * 첫째, 종료 배치는 issued_quantity 를 실제 행 수로 덮어쓰는 고치는 배치다. 검증을 거기
     * 붙이면 그 배치가 방금 고친 값을 그 자리에서 다시 재게 되어 언제나 깨끗하게 나온다.
     * 보정이 실패한 것을 잡아내는 것이 검증의 값인데 그 값이 사라진다.
     *
     * 둘째, 종료 배치는 10분 주기다. 300만 건 전체를 훑는 일을 그 주기로 돌릴 수 없고, 마감
     * 경로에 무거운 읽기를 붙이면 이벤트를 제때 끄는 일이 그만큼 밀린다.
     *
     * 셋째, 다섯 항목이 재기 좋은 시점을 서로 공유하지 않는다. issued_quantity 는 종료 배치가
     * 그 쿠폰을 끄기 전까지 어긋나 있는 것이 정상이라 그 창에서 재면 정상인 것을 어긋남으로
     * 보고하고, 상태와 이력은 만료 배치가 돈 뒤라야 그날 만료분이 반영된 값을 잰다.
     *
     * 그래서 이 배치는 모든 보정 경로가 제 일을 할 기회를 가진 뒤에 돈다. 만료 배치(04:00)
     * 뒤이고, 10분마다 도는 종료 배치는 그 시각까지 이미 여러 번 지났다.
     *
     * 순번의 연속성은 이벤트가 도는 중에는 일시적으로 구멍이 있는 것으로 잡힌다. 번호를 받고
     * 아직 안 들어간 요청이 그렇게 보이고, 그 요청이 결판나면 메워진다.
     *
     * 알려진 상한이 하나 있다. 300만 건에서 상태와 이력 대조 한 쿼리가 9초 안팎이고 전역
     * socketTimeout 이 10초다. 데이터가 더 늘면 그 쿼리가 먼저 끊긴다. 배치 프로필의
     * socketTimeout 을 따로 늘리는 것이 그때의 대응이다.
     */
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void verifyConsistency() {
        couponConsistencyService.verify();
    }
}
