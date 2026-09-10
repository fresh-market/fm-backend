package com.freshmarket.order.internal.repository;

import com.freshmarket.order.internal.entity.OrderPaymentRequestOutbox;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderPaymentRequestOutboxRepository extends JpaRepository<OrderPaymentRequestOutbox, Long> {

    Optional<OrderPaymentRequestOutbox> findByOrderIdAndDispatchedFalse(Long orderId);

    List<OrderPaymentRequestOutbox> findByDispatchedFalseAndIdGreaterThanOrderByIdAsc(Long afterId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderPaymentRequestOutbox o where o.id = :outboxId")
    Optional<OrderPaymentRequestOutbox> findByIdForUpdate(@Param("outboxId") Long outboxId);
}
