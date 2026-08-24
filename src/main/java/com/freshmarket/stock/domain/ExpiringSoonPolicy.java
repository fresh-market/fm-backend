package com.freshmarket.stock.domain;

// 소비기한 임박 조회의 정책 값. 컨트롤러(요청 파라미터 기본값/상한)와 서비스가 공유한다
public final class ExpiringSoonPolicy {

    public static final int DEFAULT_WITHIN_DAYS = 3;
    public static final int MAX_WITHIN_DAYS = 30;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private ExpiringSoonPolicy() {
    }
}