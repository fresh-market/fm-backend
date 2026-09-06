package com.freshmarket.payment.internal.repository;

import com.freshmarket.payment.internal.entity.PaymentResultOutbox;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentResultOutboxRepository extends JpaRepository<PaymentResultOutbox, Long> {

    Optional<PaymentResultOutbox> findByPaymentIdAndDispatchedFalse(Long paymentId);

    List<PaymentResultOutbox> findByDispatchedFalseAndIdGreaterThanOrderByIdAsc(Long afterId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentResultOutbox o where o.id = :outboxId")
    Optional<PaymentResultOutbox> findByIdForUpdate(@Param("outboxId") Long outboxId);
}
