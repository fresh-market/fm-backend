package com.freshmarket.product.internal.service;

import com.freshmarket.product.internal.repository.ProductOptionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductOptionAvailabilityService {

    private final ProductOptionRepository productOptionRepository;

    /*
     * (DI-2-01) 0건 갱신은 두 원인이 섞여 있어 구분해야 한다.
     * - occurredAt보다 더 최신 사실이 이미 반영돼 있는 경우: 정상. 최신 값이 오래된 값에 덮이지
     *   않도록 막은 것뿐이라 조용히 넘어간다.
     * - 옵션 자체가 존재하지 않는 경우: 이벤트가 가리키는 대상이 없다는 데이터 정합성 문제라
     *   구분 없이 넘기면 유실을 놓친다. 예외를 던져 호출부(리스너/재시도 서비스)가 이미 갖춘
     *   실패 기록·재시도 경로(DI-6-01 아웃박스)를 그대로 타게 한다.
     */
    public void updateSoldOut(Long productOptionId, boolean soldOut, LocalDateTime occurredAt) {
        int updated = productOptionRepository.updateSoldOutIfNewer(productOptionId, soldOut, occurredAt);
        if (updated == 0 && !productOptionRepository.existsById(productOptionId)) {
            throw new IllegalStateException("존재하지 않는 옵션을 대상으로 한 품절 여부 이벤트다: productOptionId=" + productOptionId);
        }
    }
}
