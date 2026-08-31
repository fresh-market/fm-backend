package com.freshmarket.payment.internal.repository;

import com.freshmarket.payment.internal.entity.Payment;
import java.time.LocalDateTime;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);

    /*
     * uk_payment_order(order_id)를 원자적 "이미 있으면 아무것도 하지 않음" 연산으로 쓴다.
     * 조회 후 save 방식은 동시 요청 두 건이 모두 PENDING을 만들 수 있다.
     */
    @Modifying
    @Query(value = """
            insert into payment (order_id, method, amount, status, refunded_amount, created_at, updated_at)
            values (:orderId, :method, :amount, 'PENDING', 0, :now, :now)
            on duplicate key update order_id = order_id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") Long orderId,
                       @Param("method") String method,
                       @Param("amount") int amount,
                       @Param("now") LocalDateTime now);
}
