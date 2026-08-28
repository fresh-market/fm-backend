package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.domain.service.CouponConsistencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 정합성 검증을 배치가 하루 한 번 돌린다.
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
     * 이벤트 종료 배치에 붙이지 않고 따로 두는 이유가 셋이다.
     *
     * 첫째, 종료 배치의 정리는 issued_quantity 를 실제 행 수로 덮어쓰는 고치는 일이다.
     * 검증을 거기 붙이면 방금 고친 값을 재게 되어 언제나 깨끗하게 나온다. 게다가 정리는 후보를
     * 7일 안으로 끊는데, 그 상한을 넘겨 어긋난 채 남은 쿠폰이 바로 검증이 찾아야 할 대상이다.
     *
     * 둘째, 종료 배치는 1분과 10분 주기다. 300만 건 전체 스캔을 그 주기로 못 돌리고, 마감
     * 경로에 무거운 읽기를 붙이면 끄기가 공짜라는 성질이 사라져 마감이 밀린다.
     *
     * 셋째, 다섯 항목이 좋은 시점을 공유하지 않는다. issued_quantity 는 종료 직후 정리가 돌기
     * 전까지 어긋나 있는 것이 정상이라, 그 창에서 재면 정상인 것을 어긋남으로 보고한다.
     * 상태와 이력은 만료 배치 뒤라야 그날 만료분이 반영된 값을 잰다.
     *
     * 그래서 모든 보정 경로가 제 일을 할 기회를 가진 뒤에 둔다. 만료(04:00) 뒤이고, 10분마다
     * 도는 정리는 이미 여러 번 지났을 시각이다.
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
