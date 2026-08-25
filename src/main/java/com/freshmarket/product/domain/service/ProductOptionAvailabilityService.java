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
     * (DI-2-01) 0건 갱신이 "대상 없음"인지 "이미 더 최신 사실이 반영됨"인지 구분한다.
     * 후자는 낡은 이벤트를 정상적으로 무시한 것이라 조용히 넘어가지만, 전자는 실제로 반영에
     * 실패한 것이라 예외를 던져 호출부(OptionAvailabilityEventListener)가 재시도 큐로 보내게 한다.
     * 예외를 던져도 안전한 이유는 그 리스너가 AFTER_COMMIT에서 이 예외를 잡아 삼키기 때문이다 —
     * 원 요청 스레드로 전파되지 않으니, 이미 성공적으로 끝난 로트 입고 요청이 뒤늦게 500으로
     * 뒤집히는 일은 없다.
     */
    public void updateSoldOut(Long productOptionId, boolean soldOut, LocalDateTime occurredAt) {
        int updatedRows = productOptionRepository.updateSoldOutIfNewer(productOptionId, soldOut, occurredAt);
        if (updatedRows == 0 && !productOptionRepository.existsById(productOptionId)) {
            throw new IllegalStateException(
                    "대상 옵션을 찾을 수 없어 품절 여부를 갱신하지 못했다. productOptionId=" + productOptionId);
        }
    }
}
