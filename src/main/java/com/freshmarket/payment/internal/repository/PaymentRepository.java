package com.freshmarket.payment.internal.repository;

import com.freshmarket.payment.PaymentStatus;
import com.freshmarket.payment.internal.entity.Payment;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
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

    /*
     * [2026-09-05 18:28 KST] 복구 배치가 재확인할 UNKNOWN 후보를 페이지 단위로 훑는다.
     * PendingProductImageCleanupService.findByUploadStatusAndIdGreaterThanAndCreatedAtBeforeOrderByIdAsc와
     * 같은 방식 — id 기준 커서로 페이지를 넘기면 한 페이지 처리 중 다른 행이 새로 UNKNOWN이 되어도
     * 중복/누락 없이 다음 페이지로 넘어간다. updatedAt이 markUnknown() 시점이라, 그 시점 기준으로
     * 유예 시간이 지난 것만 대상으로 삼는다.
     */
    List<Payment> findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
            PaymentStatus status, Long afterId, LocalDateTime cutoff, Pageable pageable);
}
