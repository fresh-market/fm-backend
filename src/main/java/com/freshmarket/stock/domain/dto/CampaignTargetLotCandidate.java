package com.freshmarket.stock.domain.dto;

import com.querydsl.core.annotations.QueryProjection;
import java.time.LocalDate;

// CampaignTargetLotBatch 내부에서만 쓰는 QueryDSL 프로젝션. 소진율 계산에 필요한 원본 값만 담는다
public record CampaignTargetLotCandidate(
        Long stockLotId,
        Long productOptionId,
        LocalDate expiryDate,
        int initialQty,
        int availableQty
) {
    // QueryDSL APT 가 @QueryProjection 을 놓을 자리로 compact 생성자만 필요할 뿐, 로직은 없다
    @QueryProjection
    public CampaignTargetLotCandidate {
    }
}
