package com.freshmarket.product.domain.repository;

import com.freshmarket.product.domain.entity.OptionAvailabilitySyncFailure;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OptionAvailabilitySyncFailureRepository extends JpaRepository<OptionAvailabilitySyncFailure, Long> {

    Optional<OptionAvailabilitySyncFailure> findByProductOptionId(Long productOptionId);

    /*
     * (FUN-3-03/PERF-4-03) retryAllPending()이 findAll()로 전체를 한 번에 메모리에 올리지 않도록
     * id 기준 청크로 나눠 읽는다. id 순으로 계속 전진하므로 처리 중 행이 지워져도(markSucceeded)
     * 다음 청크가 밀리거나 건너뛰지 않는다(OFFSET 페이징의 약점을 피한다).
     */
    List<OptionAvailabilitySyncFailure> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);
}
