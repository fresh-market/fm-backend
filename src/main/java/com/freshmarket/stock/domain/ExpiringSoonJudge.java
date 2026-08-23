package com.freshmarket.stock.domain;

import com.freshmarket.stock.domain.dto.StockLotView;
import java.time.LocalDate;

/*
 * 소비기한 임박 판정 로직만 떼어낸 순수 함수 모음.
 * ExpiringSoonService 에서 분리한 이유는, QueryDSL(JPAQueryFactory) 의존성 없이
 * 판정 계산 자체를 단위 테스트로 직접 검증하기 위해서다.
 *
 * domain.service 패키지에 두면 ArchitectureTest 의 "~Service 로 끝나야 한다" 규칙에
 * 걸려서(이건 서비스가 아니라 판정 유틸이라) domain 바로 아래에 둔다.
 */
public final class ExpiringSoonJudge {

    private ExpiringSoonJudge() {
    }

    // 판매 마감 기한(소비기한 - saleAvailableDaysFromExpiry) 이 판단 시작일 이내인지 본다
    public static boolean isExpiringSoon(
            StockLotView lot, int saleAvailableDaysFromExpiry, LocalDate judgmentStart) {
        LocalDate saleDeadline = lot.expiryDate().minusDays(saleAvailableDaysFromExpiry);
        return !saleDeadline.isAfter(judgmentStart);
    }
}
