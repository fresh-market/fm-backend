package com.freshmarket.product.domain.repository;

import com.freshmarket.product.domain.entity.OptionAvailabilitySyncFailure;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OptionAvailabilitySyncFailureRepository extends JpaRepository<OptionAvailabilitySyncFailure, Long> {

    Optional<OptionAvailabilitySyncFailure> findByProductOptionId(Long productOptionId);
}
