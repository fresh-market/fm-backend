package com.freshmarket.product.domain.service;

import com.freshmarket.product.domain.repository.ProductOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductOptionAvailabilityService {

    private final ProductOptionRepository productOptionRepository;

    /*
     * 옵션이 존재하지 않아도 예외를 던지지 않고 조용히 넘어간다.
     * AFTER_COMMIT 리스너에서 예외가 나면 원 요청 스레드로 동기 전파돼, 이미 성공적으로
     * 끝난 로트 입고 요청이 뒤늦게 500으로 뒤집힐 수 있다.
     */
    public void updateSoldOut(Long productOptionId, boolean soldOut) {
        productOptionRepository.findById(productOptionId)
                .ifPresent(option -> option.updateSoldOut(soldOut));
    }
}
