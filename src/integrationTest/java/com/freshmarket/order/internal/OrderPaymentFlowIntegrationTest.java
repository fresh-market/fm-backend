package com.freshmarket.order.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.order.internal.dto.OrderCreateItemRequest;
import com.freshmarket.order.internal.dto.OrderCreateRequest;
import com.freshmarket.order.internal.dto.OrderCreateResponse;
import com.freshmarket.order.internal.entity.OrderStatus;
import com.freshmarket.order.internal.service.OrderCreateService;
import com.freshmarket.payment.internal.client.FakePaymentGatewayIntegrationTest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 주문 생성 -> 결제 요청 이벤트 -> PG 응답 -> 결제/주문/재고 확정까지, order/payment/stock 세 도메인이
 * 실제 스프링 빈과 실제 MySQL로 맞물리는지 검증한다. OrderCreateService.createOrder()를 컨트롤러를
 * 거치지 않고 직접 호출한다 — 이 테스트의 관심사는 HTTP/보안 계층이 아니라 결제 이벤트 체인이므로,
 * @AuthenticationPrincipal 인증 셋업 없이 바로 서비스 빈을 부르는 쪽이 더 간단하고 목적에 맞는다.
 *
 * cartItemIds 대신 items(바로구매)로 요청을 만든다 — CartApi/cart/cart_item까지 채울 필요 없이
 * ProductApi/product_option만 준비하면 되기 때문이다.
 *
 * onPaymentRequested/onPaymentApproved/onPaymentFailed 세 리스너 모두 @Async 없이 완전 동기
 * (같은 스레드)로 실행된다(PaymentRequestedEventListener 클래스 주석 참고) — 그래서 createOrder()
 * 호출이 리턴하는 시점엔 이미 최종 상태까지 반영돼 있다. 폴링(Awaitility 등) 없이 바로 assert한다.
 * 나중에 이 체인 어딘가 @Async가 붙는 결정이 실제로 나면, 그때 이 테스트들도 폴링 방식으로 바꾼다.
 */
@SpringBootTest
class OrderPaymentFlowIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private OrderCreateService orderCreateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakePaymentGatewayIntegrationTest fakePaymentGateway;

    private Long memberId;
    private Long addressId;
    private Long categoryId;
    private Long supplierId;
    private Long productId;
    private Long productOptionId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
        orderId = null;
        memberId = insertMember();
        addressId = insertAddress(memberId);
        productOptionId = insertPurchasableOption(12_900);
        insertStockLot(productOptionId, 100);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 결제가_승인되면_주문은_PAID_재고는_CONFIRMED가_된다() {
        fakePaymentGateway.willApprove();

        OrderCreateResponse response = orderCreateService.createOrder(memberId, request());
        orderId = response.orderId();

        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PAID);
        assertThat(paymentStatus(orderId)).isEqualTo("PAID");
        assertThat(stockAllocationStatuses(orderId)).containsOnly("CONFIRMED");
        assertThat(orderPaymentRequestOutboxDispatched(orderId)).isTrue();
        assertThat(paymentResultOutboxDispatched(orderId)).isTrue();
        assertThat(fakePaymentGateway.callCount()).isEqualTo(1);
    }

    @Test
    void 결제가_거절되면_주문은_CANCELED_재고는_RELEASED가_된다() {
        fakePaymentGateway.willReject("카드 한도 초과");

        OrderCreateResponse response = orderCreateService.createOrder(memberId, request());
        orderId = response.orderId();

        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CANCELED);
        assertThat(paymentStatus(orderId)).isEqualTo("FAILED");
        assertThat(stockAllocationStatuses(orderId)).containsOnly("RELEASED");
        assertThat(orderPaymentRequestOutboxDispatched(orderId)).isTrue();
        assertThat(paymentResultOutboxDispatched(orderId)).isTrue();
    }

    /*
     * PG timeout/응답유실이면 결제는 UNKNOWN으로 남고 order/재고는 아무 판단도 하지 않는다 —
     * 이 경우를 해소하는 건 리컨실 배치의 몫이다(PaymentReconciliationService, 별도 테스트에서 검증).
     * 여기서는 "성급하게 확정하지 않는다"만 확인한다.
     */
    @Test
    void PG_응답이_불확실하면_주문과_재고는_그대로_대기한다() {
        fakePaymentGateway.willTimeout();

        OrderCreateResponse response = orderCreateService.createOrder(memberId, request());
        orderId = response.orderId();

        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(paymentStatus(orderId)).isEqualTo("UNKNOWN");
        assertThat(stockAllocationStatuses(orderId)).containsOnly("RESERVED");
        assertThat(orderPaymentRequestOutboxDispatched(orderId)).isTrue();
    }

    private OrderCreateRequest request() {
        return new OrderCreateRequest(
                "it-" + UUID.randomUUID(),
                null,
                List.of(new OrderCreateItemRequest(productOptionId, 2)),
                addressId,
                null);
    }

    private OrderStatus orderStatus(Long orderId) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_id = ?", String.class, orderId);
        return OrderStatus.valueOf(status);
    }

    private String paymentStatus(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment WHERE order_id = ?", String.class, orderId);
    }

    private List<String> stockAllocationStatuses(Long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT sa.status FROM stock_allocation sa "
                        + "JOIN order_item oi ON oi.order_item_id = sa.order_item_id "
                        + "WHERE oi.order_id = ?",
                String.class, orderId);
    }

    private boolean orderPaymentRequestOutboxDispatched(Long orderId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT dispatched FROM order_payment_request_outbox WHERE order_id = ?", Boolean.class, orderId));
    }

    private boolean paymentResultOutboxDispatched(Long orderId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT dispatched FROM payment_result_outbox WHERE order_id = ?", Boolean.class, orderId));
    }

    // ---- fixture: 이 테스트 하나가 만든 행만 정확히 지운다. MySQL 컨테이너는 다른 통합테스트
    // 클래스와 공유하므로, 다른 데이터까지 지우지 않도록 전부 방금 만든 id로 좁혀서 지운다.

    private Long insertMember() {
        Long gradeId = jdbcTemplate.queryForObject(
                "SELECT member_grade_id FROM member_grade WHERE is_default = TRUE", Long.class);
        String providerUserId = "it-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO member (provider, provider_user_id, name, phone, member_grade_id, "
                        + "is_marketing_agreed, status, created_at, updated_at) "
                        + "VALUES ('KAKAO', ?, '통합테스트회원', '01000000000', ?, FALSE, 'ACTIVE', NOW(6), NOW(6))",
                providerUserId, gradeId);
        return jdbcTemplate.queryForObject(
                "SELECT member_id FROM member WHERE provider_user_id = ?", Long.class, providerUserId);
    }

    private Long insertAddress(Long memberId) {
        jdbcTemplate.update(
                "INSERT INTO address (member_id, recipient, phone, zipcode, road_address, "
                        + "detail_address, created_at, updated_at) "
                        + "VALUES (?, '홍길동', '01012345678', '06234', '서울 강남구 테헤란로 1', NULL, NOW(6), NOW(6))",
                memberId);
        return jdbcTemplate.queryForObject(
                "SELECT address_id FROM address WHERE member_id = ?", Long.class, memberId);
    }

    private Long insertPurchasableOption(int price) {
        String code = "it-" + UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO category (name, created_at, updated_at) VALUES (?, NOW(6), NOW(6))", code + "-cat");
        categoryId = jdbcTemplate.queryForObject(
                "SELECT category_id FROM category WHERE name = ?", Long.class, code + "-cat");

        jdbcTemplate.update(
                "INSERT INTO supplier (name, created_at, updated_at) VALUES (?, NOW(6), NOW(6))", code + "-sup");
        supplierId = jdbcTemplate.queryForObject(
                "SELECT supplier_id FROM supplier WHERE name = ?", Long.class, code + "-sup");

        jdbcTemplate.update(
                "INSERT INTO product (product_code, request_id, name, category_id, supplier_id, storage_type, "
                        + "created_at, updated_at) VALUES (?, ?, '통합테스트 상품', ?, ?, 'ROOM', NOW(6), NOW(6))",
                code, code + "-req", categoryId, supplierId);
        productId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM product WHERE product_code = ?", Long.class, code);

        jdbcTemplate.update(
                "INSERT INTO product_option (product_id, name, price, created_at, updated_at) "
                        + "VALUES (?, '기본', ?, NOW(6), NOW(6))",
                productId, price);
        return jdbcTemplate.queryForObject(
                "SELECT product_option_id FROM product_option WHERE product_id = ?", Long.class, productId);
    }

    private void insertStockLot(Long productOptionId, int qty) {
        LocalDate today = LocalDate.now();
        jdbcTemplate.update(
                "INSERT INTO stock_lot (product_option_id, request_id, received_date, expiry_date, initial_qty, "
                        + "available_qty, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                productOptionId, "it-" + UUID.randomUUID(), today, today.plusDays(30), qty, qty);
    }

    private void cleanUp() {
        if (orderId != null) {
            jdbcTemplate.update("DELETE FROM stock_movement WHERE order_id = ?", orderId);
            jdbcTemplate.update(
                    "DELETE sa FROM stock_allocation sa "
                            + "JOIN order_item oi ON oi.order_item_id = sa.order_item_id "
                            + "WHERE oi.order_id = ?",
                    orderId);
            jdbcTemplate.update("DELETE FROM payment_result_outbox WHERE order_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM payment WHERE order_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM order_payment_request_outbox WHERE order_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM order_item WHERE order_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM orders WHERE order_id = ?", orderId);
        }
        jdbcTemplate.update("DELETE FROM stock_lot WHERE product_option_id = ?", productOptionId);
        jdbcTemplate.update("DELETE FROM address WHERE address_id = ?", addressId);
        jdbcTemplate.update("DELETE FROM product_option WHERE product_option_id = ?", productOptionId);
        jdbcTemplate.update("DELETE FROM product WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM category WHERE category_id = ?", categoryId);
        jdbcTemplate.update("DELETE FROM supplier WHERE supplier_id = ?", supplierId);
        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
    }
}
