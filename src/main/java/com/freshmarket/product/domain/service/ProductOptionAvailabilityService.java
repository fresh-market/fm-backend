package com.freshmarket.product.domain.service;

import com.freshmarket.product.domain.repository.ProductOptionRepository;
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
     * 옵션이 존재하지 않거나(0건 갱신), occurredAt보다 더 최신 사실이 이미 반영돼 있어도(DI-2-01,
     * 역시 0건 갱신) 예외를 던지지 않고 조용히 넘어간다.
     * AFTER_COMMIT 리스너에서 예외가 나면 원 요청 스레드로 동기 전파돼, 이미 성공적으로 끝난 로트
     * 입고 요청이 뒤늦게 500으로 뒤집힐 수 있다.
     */
    public void updateSoldOut(Long productOptionId, boolean soldOut, LocalDateTime occurredAt) {
        productOptionRepository.updateSoldOutIfNewer(productOptionId, soldOut, occurredAt);
    }
}
