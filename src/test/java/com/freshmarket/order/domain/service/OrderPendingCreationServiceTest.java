package com.freshmarket.order.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.cart.CartApi;
import com.freshmarket.cart.CartCheckoutInfo;
import com.freshmarket.cart.CartCheckoutItem;
import com.freshmarket.common.auth.jwt.TokenHasher;
import com.freshmarket.member.AddressInfo;
import com.freshmarket.member.MemberApi;
import com.freshmarket.order.domain.OrderNoGenerator;
import com.freshmarket.order.domain.PendingOrderResult;
import com.freshmarket.order.domain.dto.OrderCreateRequest;
import com.freshmarket.order.domain.dto.OrderCreateItemRequest;
import com.freshmarket.order.domain.entity.Order;
import com.freshmarket.order.domain.entity.OrderItem;
import com.freshmarket.order.domain.entity.OrderPlacement;
import com.freshmarket.order.domain.entity.OrderStatus;
import com.freshmarket.order.domain.exception.OrderErrorCode;
import com.freshmarket.order.domain.exception.OrderException;
import com.freshmarket.order.domain.repository.OrderItemRepository;
import com.freshmarket.order.domain.repository.OrderRepository;
import com.freshmarket.stock.StockApi;
import com.freshmarket.stock.StockReservationRequest;
import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderPendingCreationServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private CartApi cartApi;

    @Mock
    private MemberApi memberApi;

    @Mock
    private StockApi stockApi;

    @Mock
    private ProductApi productApi;

    @Mock
    private OrderNoGenerator orderNoGenerator;

    private OrderPendingCreationService sut;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-21T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        sut = new OrderPendingCreationService(
                orderRepository, orderItemRepository, cartApi, memberApi, stockApi, productApi, orderNoGenerator, clock);
    }

    @Test
    void 새_요청이면_주문과_주문상품을_만들고_재고를_예약하고_장바구니를_비운다() {
        when(orderRepository.findByRequestId("req-1")).thenReturn(Optional.empty());
        when(memberApi.findAddress(10L, MEMBER_ID)).thenReturn(Optional.of(address()));
        when(cartApi.getCheckoutItems(MEMBER_ID, List.of(1L, 2L))).thenReturn(checkoutInfo());
        when(orderNoGenerator.generate()).thenReturn("TEMP-XYZ");
        stubSave();

        PendingOrderResult result = sut.createPendingOrder(MEMBER_ID, request());

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.response().orderId()).isEqualTo(100L);
        assertThat(result.response().orderNo()).isEqualTo("100");
        assertThat(result.response().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(result.response().totalAmount()).isEqualTo(38_700);

        ArgumentCaptor<StockReservationRequest> captor = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(stockApi).reserve(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(100L);
        assertThat(captor.getValue().items()).hasSize(2);

        verify(cartApi).removeCheckedOutItems(MEMBER_ID, checkoutInfo().items());
    }

    @Test
    void 같은_requestId와_같은_내용이면_기존_주문을_그대로_반환한다() {
        OrderCreateRequest request = request();
        Order existing = existingOrder("req-1", requestHash(MEMBER_ID, request));
        ReflectionTestUtils.setField(existing, "id", 200L);
        when(orderRepository.findByRequestId("req-1")).thenReturn(Optional.of(existing));

        PendingOrderResult result = sut.createPendingOrder(MEMBER_ID, request);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.response().orderId()).isEqualTo(200L);
        verify(memberApi, never()).findAddress(any(), any());
        verify(cartApi, never()).getCheckoutItems(any(), any());
        verify(stockApi, never()).reserve(any());
    }

    @Test
    void 같은_requestId가_다른_내용이면_예외를_던진다() {
        Order existing = existingOrder("req-1", "f".repeat(64));
        when(orderRepository.findByRequestId("req-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> sut.createPendingOrder(MEMBER_ID, request()))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.DUPLICATE_REQUEST);

        verify(cartApi, never()).getCheckoutItems(any(), any());
    }

    @Test
    void 배송지를_찾을_수_없으면_예외를_던진다() {
        when(orderRepository.findByRequestId("req-1")).thenReturn(Optional.empty());
        when(memberApi.findAddress(10L, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.createPendingOrder(MEMBER_ID, request()))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.ADDRESS_NOT_FOUND);

        verify(cartApi, never()).getCheckoutItems(any(), any());
    }

    @Test
    void 재고_예약이_실패하면_예외가_전파되고_장바구니는_비우지_않는다() {
        when(orderRepository.findByRequestId("req-1")).thenReturn(Optional.empty());
        when(memberApi.findAddress(10L, MEMBER_ID)).thenReturn(Optional.of(address()));
        when(cartApi.getCheckoutItems(MEMBER_ID, List.of(1L, 2L))).thenReturn(checkoutInfo());
        when(orderNoGenerator.generate()).thenReturn("TEMP-XYZ");
        stubSave();
        doThrow(new IllegalStateException("재고 부족")).when(stockApi).reserve(any());

        assertThatThrownBy(() -> sut.createPendingOrder(MEMBER_ID, request()))
                .isInstanceOf(IllegalStateException.class);

        verify(cartApi, never()).removeCheckedOutItems(any(), any());
    }

    @Test
    void 바로구매면_상품정보로_주문스냅샷을_만들고_장바구니는_건드리지_않는다() {
        OrderCreateRequest request = directRequest();
        when(orderRepository.findByRequestId("direct-1")).thenReturn(Optional.empty());
        when(memberApi.findAddress(10L, MEMBER_ID)).thenReturn(Optional.of(address()));
        when(productApi.findOptionInfos(List.of(20L))).thenReturn(List.of(
                new ProductOptionInfo(20L, "감귤 1kg", "1kg", 12_900, true)));
        when(orderNoGenerator.generate()).thenReturn("TEMP-XYZ");
        stubSave();

        PendingOrderResult result = sut.createPendingOrder(MEMBER_ID, request);

        assertThat(result.response().totalAmount()).isEqualTo(28_800);
        verify(cartApi, never()).getCheckoutItems(any(), any());
        verify(cartApi, never()).removeCheckedOutItems(any(), any());
        verify(stockApi).reserve(any());
    }

    @Test
    void 장바구니와_바로구매를_함께_보내면_거절한다() {
        OrderCreateRequest request = new OrderCreateRequest("mixed-1", List.of(1L),
                List.of(new OrderCreateItemRequest(20L, 1)), 10L, null);

        assertThatThrownBy(() -> sut.createPendingOrder(MEMBER_ID, request))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.ORDER_ITEMS_MIXED);

        verify(cartApi, never()).getCheckoutItems(any(), any());
        verify(productApi, never()).findOptionInfos(any());
    }

    private void stubSave() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 100L);
            return order;
        });
        when(orderItemRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<OrderItem> items = invocation.getArgument(0);
            long id = 500L;
            for (OrderItem item : items) {
                ReflectionTestUtils.setField(item, "id", id++);
            }
            return items;
        });
    }

    private OrderCreateRequest request() {
        return new OrderCreateRequest("req-1", List.of(1L, 2L), null, 10L, "문 앞에 놔주세요");
    }

    private OrderCreateRequest directRequest() {
        return new OrderCreateRequest("direct-1", null,
                List.of(new OrderCreateItemRequest(20L, 2)), 10L, "문 앞에 놔주세요");
    }

    private AddressInfo address() {
        return new AddressInfo(10L, "홍길동", "01012345678", "06234", "테헤란로 1", "101동 202호");
    }

    private CartCheckoutInfo checkoutInfo() {
        return new CartCheckoutInfo(5L, List.of(
                new CartCheckoutItem(1L, 20L, "감귤 1kg", "1kg", 12_900, 2),
                new CartCheckoutItem(2L, 21L, "사과 1kg", "1kg", 9_900, 1)
        ));
    }

    private Order existingOrder(String requestId, String requestHash) {
        return Order.place(new OrderPlacement(
                requestId, requestHash, MEMBER_ID, "ORD-EXISTING", 25_800, 0, 3_000, 28_800,
                "홍길동", "01012345678", "06234", "서울 강남구 테헤란로 1", null,
                LocalDateTime.of(2026, 8, 21, 12, 0)));
    }

    // OrderPendingCreationService.computeRequestHash와 같은 규칙으로 기대 해시를 계산한다.
    private String requestHash(Long memberId, OrderCreateRequest request) {
        String checkoutKey = request.cartItemIds() != null && !request.cartItemIds().isEmpty()
                ? "cart:" + request.cartItemIds().stream().distinct().sorted()
                .map(String::valueOf).collect(Collectors.joining(","))
                : "direct:" + request.items().stream()
                .map(item -> item.productOptionId() + ":" + item.qty()).sorted()
                .collect(Collectors.joining(","));
        String canonical = memberId + "|" + checkoutKey + "|" + request.addressId() + "|"
                + (request.shipMessage() == null ? "" : request.shipMessage());
        return TokenHasher.sha256(canonical);
    }
}
