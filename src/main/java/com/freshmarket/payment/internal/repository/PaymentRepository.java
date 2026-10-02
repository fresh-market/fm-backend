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
     * [2026-09-27 KST] confirm API(PaymentConfirmationService)가 결제 확정을 시작하기 전에 이
     * 결제 행을 잠근다 — confirm 처리 도중, 복구 배치(PaymentReconciliationService)가 같은 행을
     * 먼저 잠그고 UNKNOWN을 PAID/FAILED로 확정해버리는 경합을 막는다. findByIdForUpdate와 같은
     * 락 방식이지만, confirm API는 결제 준비 응답에 담겼던 orderId만 알고 payment_id는 모른다
     * (프론트가 굳이 payment_id를 따로 저장/전달할 이유가 없다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    /*
     * uk_payment_order(order_id)를 원자적 "이미 있으면 아무것도 하지 않음" 연산으로 쓴다.
     * 조회 후 save 방식은 동시 요청 두 건이 모두 PENDING을 만들 수 있다.
     *
     * [2026-09-27 KST] member_id, pg_order_no 컬럼을 추가했다 — 둘 다 Payment 엔티티 생성자에서
     * 함께 계산/검증하는 값이라(Payment.prepare 참고), 네이티브 upsert에도 그대로 실어야 엔티티를
     * 다시 읽어왔을 때(findByOrderId) 일관된 값을 얻는다.
     */
    @Modifying
    @Query(value = """
            insert into payment (order_id, pg_order_no, member_id, method, amount, status, refunded_amount, created_at, updated_at)
            values (:orderId, :pgOrderNo, :memberId, :method, :amount, 'PENDING', 0, :now, :now)
            on duplicate key update order_id = order_id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") Long orderId,
                       @Param("pgOrderNo") String pgOrderNo,
                       @Param("memberId") Long memberId,
                       @Param("method") String method,
                       @Param("amount") int amount,
                       @Param("now") LocalDateTime now);

    /*
     * [2026-09-05 18:28 KST] 복구 배치가 재확인할 후보를 상태별로 페이지 단위로 훑는다. status를
     * 파라미터로 받으므로 UNKNOWN(PaymentReconciliationService.reconcileUnknownPayments)과
     * PENDING(reconcilePendingPayments, 2026-09-06 추가) 양쪽 모두 이 메서드 하나를 그대로
     * 재사용한다 — 조회 로직 자체는 대상 상태가 무엇이든 동일하기 때문이다.
     * PendingProductImageCleanupService.findByUploadStatusAndIdGreaterThanAndCreatedAtBeforeOrderByIdAsc와
     * 같은 방식 — id 기준 커서로 페이지를 넘기면 한 페이지 처리 중 다른 행이 새로 같은 상태가 되어도
     * 중복/누락 없이 다음 페이지로 넘어간다. updatedAt이 그 상태로 전이된 시점이라, 그 시점 기준으로
     * (상태별로 다른) 유예 시간이 지난 것만 대상으로 삼는다.
     *
     * [2026-09-27 KST] pgTid IS NOT NULL 조건을 추가했다 — 결제창을 열어놓고 그냥 이탈한 경우
     * (confirm을 한 번도 호출 안 함, Payment.recordConfirmAttempt 클래스 주석 참고)는 pgTid가 끝까지
     * 비어 있어 토스 쪽에 물어볼 거래 자체가 없다. 이런 행까지 매 주기 inquire()로 재조회하면 PG에
     * 의미 없는 호출만 쌓인다. UNKNOWN은 markUnknown()이 항상 recordConfirmAttempt() 이후에만
     * 일어나므로(PaymentConfirmationService.confirm 참고) 이 조건이 사실상 영향이 없지만, PENDING은
     * "confirm 시도 중 프로세스가 죽어 pgTid는 남았지만 상태 전이를 못한 경우"와 "애초에 confirm을
     * 시도조차 안 한 이탈"이 둘 다 섞여 있어 이 조건으로 후자를 걸러낸다. 이탈로 오래 PAYMENT_PENDING에
     * 머문 주문은 이 배치가 아니라 order.internal.batch.PendingOrderExpirationService가 순수 TTL로
     * 만료시킨다 — 그 배치는 payment 테이블을 전혀 보지 않고 Order.status/updatedAt만 보므로(L2끼리
     * 직접 호출 금지 규칙과 무관하게) pgTid 유무와 상관없이 이미 이 케이스를 커버하고 있었다.
     */
    List<Payment> findByStatusAndReconciliationIsolatedFalseAndPgTidIsNotNullAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
            PaymentStatus status, Long afterId, LocalDateTime cutoff, Pageable pageable);
}
