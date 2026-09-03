package com.freshmarket.stock.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/*
 * 소비기한 임박 조회의 정책 값. 컨트롤러(요청 파라미터 기본값/상한)와 서비스가 공유한다.
 *
 * withinDays 관련 값이 없다. 대상 구간은 캠페인 배치가 확정하므로 조회 쪽에서 정할 것이 아니다
 * (CampaignTargetLotBatch 의 SALE_CLOSE_DAYS / EXPIRING_SOON_DAYS 참고).
 */
public final class ExpiringSoonPolicy {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    /*
     * 캠페인 기준일을 세는 시간대.
     *
     * 배치가 확정한 기준일과 조회가 찾는 기준일이 같아야 한다. 둘이 다른 시간대로 날짜를 세면
     * 자정을 사이에 두고 하루가 어긋나 그날 목록이 통째로 비어 보인다.
     *
     * 주입받은 Clock 을 그대로 쓰지 않는 이유가 있다. ClockConfig 가 systemDefaultZone() 이라
     * 호스트 시간대를 따르는데, 이 기능의 "자정" 은 호스트가 아니라 한국 자정이다
     * (@Scheduled 의 zone 도 같은 값이다). 호스트가 UTC 면 둘이 아홉 시간 어긋난다.
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    public static final String BUSINESS_ZONE_ID = "Asia/Seoul";

    private ExpiringSoonPolicy() {
    }

    // 캠페인 기준일. 배치와 두 조회가 모두 이것으로 오늘을 정한다
    public static LocalDate businessToday(Clock clock) {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }
}
