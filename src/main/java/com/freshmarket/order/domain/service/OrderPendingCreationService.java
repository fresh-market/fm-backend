package com.freshmarket.order.domain.service;

import com.freshmarket.cart.CartApi;
import com.freshmarket.cart.CartCheckoutInfo;
import com.freshmarket.common.auth.jwt.TokenHasher;
import com.freshmarket.member.AddressInfo;
import com.freshmarket.member.MemberApi;
import com.freshmarket.order.domain.OrderNoGenerator;
import com.freshmarket.order.domain.OrderPriceCalculator;
import com.freshmarket.order.domain.PendingOrderResult;
import com.freshmarket.order.domain.dto.OrderCreateRequest;
import com.freshmarket.order.domain.dto.OrderCreateResponse;
import com.freshmarket.order.domain.entity.Order;
import com.freshmarket.order.domain.entity.OrderItem;
import com.freshmarket.order.domain.entity.OrderItemPlacement;
import com.freshmarket.order.domain.entity.OrderPlacement;
import com.freshmarket.order.domain.exception.OrderErrorCode;
import com.freshmarket.order.domain.exception.OrderException;
import com.freshmarket.order.domain.repository.OrderItemRepository;
import com.freshmarket.order.domain.repository.OrderRepository;
import com.freshmarket.stock.StockApi;
import com.freshmarket.stock.StockReservationItemRequest;
import com.freshmarket.stock.StockReservationRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 주문을 PAYMENT_PENDING까지 만들어 커밋하는 짧은 트랜잭션 하나를 담당한다. 결제 요청은 이 클래스
 * 밖(OrderCreateService)에서, 이 트랜잭션이 커밋되고 난 뒤에 별도로 이어진다 — 주문 인수인계 문서의
 * "PG 호출과 DB 트랜잭션 경계" 권장 순서(짧은 TX -> TX 밖에서 PG 호출 -> 짧은 TX) 중 첫 단계다.
 *
 * package-private다: OrderCreateService(같은 패키지)만 이 클래스를 부른다. order의 공개 진입점은
 * OrderCreateService.createOrder() 하나뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class OrderPendingCreationService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartApi cartApi;
    private final MemberApi memberApi;
    private final StockApi stockApi;
    private final OrderNoGenerator orderNoGenerator;
    private final Clock clock;

    /*
     * CartApi.getCheckoutItems가 이미 락 아래에서 최신 가격/구매가능여부를 재검증한 스냅샷을
     * 주므로, ProductApi.findOptionInfos를 여기서 다시 부르지 않는다. (주문 인수인계 문서는
     * order가 직접 ProductApi를 부르는 흐름을 권장하는데, 그 문서가 쓰였을 때는 cart의 checkout
     * 계약이 지금처럼 구체화되기 전이었을 가능성이 커 보인다 — cart의 getCheckoutItems 쪽 검증과
     * 사실상 중복이라 여기서는 생략했다. 상품/옵션이 cart 조회 시점과 order 시점 사이에 바뀌는
     * 창은 같은 트랜잭션 흐름 안 수 ms뿐이다.)
     */
    @Transactional
    PendingOrderResult createPendingOrder(Long memberId, OrderCreateRequest request) {
        String requestHash = computeRequestHash(memberId, request);

        Order existing = orderRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new OrderException(OrderErrorCode.DUPLICATE_REQUEST);
            }
            // 같은 요청의 재시도(HTTP 재전송, 중복 클릭) — 새로 만들지 않고 그대로 돌려준다.
            return new PendingOrderResult(OrderCreateResponse.from(existing), false);
        }

        AddressInfo address = memberApi.findAddress(request.addressId(), memberId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ADDRESS_NOT_FOUND));

        CartCheckoutInfo checkout = cartApi.getCheckoutItems(memberId, request.cartItemIds());

        List<OrderPriceCalculator.PriceItem> priceItems = checkout.items().stream()
                .map(item -> new OrderPriceCalculator.PriceItem(item.unitPrice(), item.qty()))
                .toList();
        OrderPriceCalculator.OrderPrice price = OrderPriceCalculator.calculate(priceItems);

        LocalDateTime orderedAt = LocalDateTime.now(clock);

        // orders.ship_address는 컬럼 하나다 — AddressInfo가 나눠 주는 도로명/상세주소를 여기서 합친다
        String shipAddress = address.roadAddress() + " " + address.detailAddress();

        Order order = Order.place(new OrderPlacement(
                request.requestId(),
                requestHash,
                memberId,
                orderNoGenerator.generate(),
                price.productAmount(),
                price.discountAmount(),
                price.shippingFee(),
                price.totalAmount(),
                address.recipient(),
                address.phone(),
                address.zipcode(),
                shipAddress,
                request.shipMessage(),
                orderedAt
        ));
        orderRepository.save(order);
        // order_id(IDENTITY)는 위 save에서 바로 생긴다 — 요청받은 정책대로 orderNo를 orderId로 되돌려 채운다.
        order.assignOrderNo(String.valueOf(order.getId()));

        List<OrderItem> items = checkout.items().stream()
                .map(item -> OrderItem.place(new OrderItemPlacement(
                        order.getId(),
                        item.cartItemId(),
                        item.productOptionId(),
                        item.productName(),
                        item.optionName(),
                        item.unitPrice(),
                        item.qty(),
                        memberId,
                        0
                )))
                .toList();
        List<OrderItem> savedItems = orderItemRepository.saveAll(items);

        List<StockReservationItemRequest> reservationItems = savedItems.stream()
                .map(item -> new StockReservationItemRequest(item.getId(), item.getProductOptionId(), item.getQty()))
                .toList();
        // 하나라도 부족하면 StockException(INSUFFICIENT_STOCK 등)이 던져지고, 이 트랜잭션 전체가
        // 롤백된다 — 방금 저장한 order/order_item도 함께 사라진다.
        stockApi.reserve(new StockReservationRequest(order.getId(), reservationItems));

        // 재고 예약이 끝난 뒤에만 장바구니에서 제거한다 — 재고가 모자라 위에서 롤백되면 장바구니는
        // 그대로 남아 있어야 한다.
        cartApi.removeCheckedOutItems(memberId, checkout.items());

        // 명령성 상태 변화 로그 — PII/토큰/pgTid 없이 orderId/상태/금액만 남긴다.
        log.info("event=order_created orderId={} status={} amount={}",
                order.getId(), order.getStatus(), order.getTotalAmount());

        return new PendingOrderResult(OrderCreateResponse.from(order), true);
    }

    // 같은 requestId가 다른 내용으로 재사용됐는지 판별하기 위한 해시다. 순서만 다른 cartItemIds는
    // 같은 요청으로 본다(정렬 후 해시).
    private String computeRequestHash(Long memberId, OrderCreateRequest request) {
        String sortedItemIds = request.cartItemIds().stream()
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        String canonical = memberId + "|" + sortedItemIds + "|" + request.addressId() + "|"
                + (request.shipMessage() == null ? "" : request.shipMessage());
        return TokenHasher.sha256(canonical);
    }
}
