package com.freshmarket.product;

import java.util.List;
import java.util.Optional;

// 다른 도메인이 상품/옵션 존재 여부나 정보를 확인할 때 쓰는 공개 창구
public interface ProductApi {

    // productId 소속으로 optionId가 실제 존재하는지 확인한다
    boolean existsOption(Long productId, Long optionId);

    // productId 소속 옵션들의 ID 목록을 반환한다. 빈 리스트면 상품이 없다는 뜻이다
    List<Long> findOptionIds(Long productId);

    // 옵션 하나의 상품명/옵션명/가격/구매가능여부를 가져온다. 없거나 삭제된 상품/옵션이면 빈 값
    Optional<ProductOptionInfo> findOptionInfo(Long productOptionId);

    // 옵션 여러 개를 한 번에 가져온다. 존재하지 않는 id는 결과에서 빠진다 (N+1 방지용 배치 조회)
    List<ProductOptionInfo> findOptionInfos(List<Long> productOptionIds);
}
