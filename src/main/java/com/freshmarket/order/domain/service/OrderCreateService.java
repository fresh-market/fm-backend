package com.freshmarket.order.domain.service;

import com.freshmarket.common.event.OrderPaymentApprovedEvent;
import com.freshmarket.common.event.OrderPaymentRequestedEvent;
import com.freshmarket.order.domain.dto.OrderCreateRequest;
import com.freshmarket.order.domain.dto.OrderCreateResponse;
import com.freshmarket.order.domain.entity.Order;
import com.freshmarket.order.domain.entity.OrderItem;
import com.freshmarket.order.domain.repository.OrderItemRepository;
import com.freshmarket.order.domain.repository.OrderRepository;
import com.freshmarket.stock.StockApi;
import com.freshmarket.stock.StockOrderItemsRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 장바구니 -> 주문 생성(POST /v1/orders)의 공개 진입점이다. 클래스/메서드 어디에도 @Transactional을
 * 달지 않는다 — 아래 세 단계가 각자 자기 트랜잭션을 갖고, 결제 요청은 열린 DB 트랜잭션이 전혀 없는
 * 상태에서 나간다. 이건 팀이 미리 정해둔 경계다(주문 인수인계 문서 5장 "PG 호출과 DB 트랜잭션 경계"):
 *
 *   a. orderPendingCreationService.createPendingOrder(...) — 짧은 트랜잭션. 주문/주문상품 저장,
 *      재고 예약, 장바구니 정리까지 끝내고 커밋한 뒤 돌아온다.
 *   b. (여기, 트랜잭션 밖) OrderPaymentRequestedEvent 발행 — payment.domain의 리스너가 이
 *      이벤트를 받아 PaymentApi.requestPayment를 부른다(자세한 이유는 그 이벤트 클래스 주석:
 *      order/payment 둘 다 L2라 서로 직접 못 부른다). 지금은 MockPaymentGateway라 이 호출이
 *      순식간에 끝나지만, 나중에 실제 PG WebClient로 바뀌어 네트워크 지연이 생겨도 이 시점엔 DB
 *      락을 하나도 쥐고 있지 않다 — PG 호출을 열린 트랜잭션 밖으로 빼는 게 이 구조의 핵심이다.
 *      "mock 성공만 리턴하는 지점"을 한 곳으로 좁히고 싶다면 손댈 곳은 이 이벤트 발행부가 아니라
 *      payment.domain.client.MockPaymentGateway 하나다 — PaymentGateway 인터페이스의 구현체를
 *      실제 PG 클라이언트로 교체하는 것만으로 끝난다(PaymentApiImpl/PaymentService는 안 바뀐다).
 *   c. onPaymentApproved(아래) — 결제가 승인되면 이벤트 체인 끝에서 새로 짧은 트랜잭션을 연다.
 *
 * a단계를 이 클래스 안의 @Transactional 메서드로 두지 않고 별도 빈(OrderPendingCreationService)
 * 으로 뺀 이유: 같은 빈 안에서 this.메서드()로 자기 자신을 호출하면 스프링 프록시를 안 거쳐
 * @Transactional이 조용히 무시된다(자기호출 함정) — TransactionTemplate으로 직접 경계를 긋는
 * 방법도 있지만, 이 프로젝트는 지금까지 전부 선언적 @Transactional만 써왔어서(TransactionTemplate
 * 쓰는 곳이 없다) 스타일을 맞추는 쪽을 택했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private final OrderPendingCreationService orderPendingCreationService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockApi stockApi;
    private final ApplicationEventPublisher eventPublisher;

    public OrderCreateResponse createOrder(Long memberId, OrderCreateRequest request) {
        PendingOrderResult pending = orderPendingCreationService.createPendingOrder(memberId, request);
        if (!pending.newlyCreated()) {
            // requestId 재시도로 기존 주문을 그대로 돌려주는 경우 — 결제를 다시 요청하지 않는다.
            return pending.response();
        }

        eventPublisher.publishEvent(new OrderPaymentRequestedEvent(
                pending.response().orderId(), pending.response().totalAmount()));

        return pending.response();
    }

    /*
     * 결제 승인(mock) 뒤 payment 도메인이 발행한 이벤트를 받아 주문을 PAID로 바꾸고 재고를
     * 확정한다. createOrder()의 a단계와는 별개의 새 트랜잭션이다(위 클래스 주석의 c단계).
     */
    @EventListener
    @Transactional
    public void onPaymentApproved(OrderPaymentApprovedEvent event) {
        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "결제 승인된 주문을 찾을 수 없습니다. orderId=" + event.orderId()));
        order.markPaid();

        // 명령성 상태 변화 로그 — PII/토큰/pgTid 없이 orderId/금액만 남긴다.
        log.info("event=order_paid orderId={} amount={}", order.getId(), order.getTotalAmount());

        List<Long> orderItemIds = orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(OrderItem::getId)
                .toList();
        stockApi.confirm(new StockOrderItemsRequest(order.getId(), orderItemIds));
    }
}
