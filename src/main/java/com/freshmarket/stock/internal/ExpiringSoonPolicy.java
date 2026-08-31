package com.freshmarket.stock.internal;

/*
 * 소비기한 임박 조회의 정책 값. 컨트롤러(요청 파라미터 기본값/상한)와 서비스가 공유한다.
 *
 * withinDays 관련 값이 없다. 대상 구간은 캠페인 배치가 확정하므로 조회 쪽에서 정할 것이 아니다
 * (CampaignTargetLotBatch 의 SALE_CLOSE_DAYS / EXPIRING_SOON_DAYS 참고).
 */
public final class ExpiringSoonPolicy {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private ExpiringSoonPolicy() {
    }
}