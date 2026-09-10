package com.freshmarket.order.internal.service;

import static com.freshmarket.common.exception.ConstraintViolations.isConstraintViolation;

import com.freshmarket.order.internal.PendingOrderResult;
import com.freshmarket.order.internal.dto.OrderCreateRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/*
 * requestId 충돌 복구의 트랜잭션 경계를 둔다. 실제 생성은 OrderPendingCreationService의
 * @Transactional 프록시를 통해 실행돼야 하며, 유니크 충돌로 그 트랜잭션이 롤백된 "뒤"에 이 빈이
 * 기존 주문을 다시 읽어야 한다. 같은 빈에서 catch하면 rollback-only 트랜잭션 안에서 조회·반환하게
 * 되어 결국 UnexpectedRollbackException으로 끝날 수 있다.
 */
@Service
@RequiredArgsConstructor
class OrderPendingCreationCoordinatorService {

    private final OrderPendingCreationService orderPendingCreationService;

    PendingOrderResult createPendingOrder(Long memberId, OrderCreateRequest request) {
        try {
            return orderPendingCreationService.createPendingOrder(memberId, request);
        } catch (DataIntegrityViolationException e) {
            if (!isConstraintViolation(e, "uk_orders_request_id")) {
                throw e;
            }
            Optional<PendingOrderResult> existing = orderPendingCreationService
                    .findExistingOrderResult(memberId, request);
            return existing.orElseThrow(() -> new IllegalStateException(
                    "request_id 유니크 위반 직후 기존 주문을 찾을 수 없습니다. requestId=" + request.requestId()));
        }
    }
}
