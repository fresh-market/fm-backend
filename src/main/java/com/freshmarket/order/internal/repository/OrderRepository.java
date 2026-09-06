package com.freshmarket.order.internal.repository;

import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdAndMemberId(Long id, Long memberId);

    // 주문 생성 요청 재시도(같은 requestId)를 같은 주문으로 수렴시키기 위한 조회다.
    Optional<Order> findByRequestId(String requestId);

    /*
     * [2026-09-06 KST] PaymentRepository.findByIdForUpdate와 같은 이유로 추가한다. 지금까지 order
     * 상태를 바꾸는 쓰기는 OrderCreateService.onPaymentApproved()/onPaymentFailed() 하나뿐이라
     * 락 없이도 문제가 없었는데, PAYMENT_PENDING 만료 배치(PendingOrderExpirationService)가
     * 생기면서 같은 주문을 바꾸려는 쓰기가 두 갈래(결제 결과 확정 / 만료 취소)로 늘었다. Order에는
     * @Version도 없어서, 두 트랜잭션이 동시에 PAYMENT_PENDING을 읽고 각자 다른 목표 상태로 커밋하면
     * 나중에 커밋하는 쪽이 그냥 덮어써버리는 lost update가 가능하다(주문 인수인계 문서 5번 섹션
     * "상태 전이 경쟁"에 이미 지적돼 있던 것). 이 조회로 잠가서 한쪽이 커밋할 때까지 다른 쪽을
     * 기다리게 하고, 락을 얻은 뒤에는 항상 최신 상태를 보고 판단하게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);

    /*
     * [2026-09-06 KST] PAYMENT_PENDING 만료 배치(PendingOrderExpirationService)가 대상을 훑는
     * 커서 조회다. PaymentRepository의 같은 이름 메서드와 동일한 패턴 — id 기준 커서로 페이지를
     * 넘기면 한 페이지 처리 중 다른 행이 새로 같은 상태가 되어도 중복/누락 없이 다음 페이지로
     * 넘어간다. updatedAt은 그 상태로 전이된 시점(PAYMENT_PENDING은 보통 생성 시점, 그 뒤로 상태가
     * 안 바뀌었다면)이라 그 시점 기준 유예 시간이 지난 것만 대상으로 삼는다. 락 없는 평범한 조회다 —
     * 실제 취소는 findByIdForUpdate로 다시 잠그고 확인하므로 그 사이 값이 바뀌어도 안전하다.
     */
    List<Order> findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
            OrderStatus status, Long afterId, LocalDateTime cutoff, Pageable pageable);
}
