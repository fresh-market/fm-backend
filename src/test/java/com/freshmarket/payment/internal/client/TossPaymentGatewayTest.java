package com.freshmarket.payment.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayRejectedException;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayUnknownException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/*
 * [2026-09-27 KST] 이 프로젝트엔 MockWebServer/WireMock 같은 별도 테스트용 HTTP 서버 라이브러리가
 * 없어서(build.gradle 확인), WebClient가 제공하는 ExchangeFunction으로 직접 응답을 만든다 — 새
 * 테스트 의존성 없이도 WebClient.builder().exchangeFunction(...)이 실제 네트워크 대신 이 가짜
 * 함수를 타게 할 수 있고, retrieve()의 4xx/5xx -> WebClientResponseException 변환까지 그대로
 * 거치므로 TossPaymentGateway가 실제로 마주칠 예외 타입을 그대로 재현한다.
 */
class TossPaymentGatewayTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T01:15:00Z"), ZoneOffset.of("+09:00"));

    @Test
    void confirm_성공하면_DONE_응답을_승인으로_변환한다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.OK, """
                {"paymentKey":"pk_abc","orderId":"ORD-00000001","status":"DONE",
                 "approvedAt":"2026-09-27T10:15:00+09:00","method":"카드"}
                """);

        PaymentGatewayApproval approval = sut.confirm("pk_abc", "ORD-00000001", 25_800);

        assertThat(approval.pgTid()).isEqualTo("pk_abc");
        assertThat(approval.method()).isEqualTo(PaymentMethod.CARD);
        assertThat(approval.paidAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 27, 10, 15, 0));
    }

    @Test
    void confirm_성공했지만_카드가_아닌_수단이면_EASY_PAY로_매핑한다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.OK, """
                {"paymentKey":"pk_abc","orderId":"ORD-00000001","status":"DONE",
                 "approvedAt":"2026-09-27T10:15:00+09:00","method":"간편결제"}
                """);

        PaymentGatewayApproval approval = sut.confirm("pk_abc", "ORD-00000001", 25_800);

        assertThat(approval.method()).isEqualTo(PaymentMethod.EASY_PAY);
    }

    /*
     * 이 프로젝트가 프론트에 노출한 결제수단은 confirm 성공 시 바로 DONE이어야 정상이다.
     * WAITING_FOR_DEPOSIT처럼 다른 status가 200으로 오면 승인으로 단정하지 않고 UNKNOWN으로
     * 넘긴다(TossPaymentGateway.confirm 주석 참고).
     */
    @Test
    void confirm_응답이_200이어도_status가_DONE이_아니면_UNKNOWN이다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.OK, """
                {"paymentKey":"pk_abc","orderId":"ORD-00000001","status":"WAITING_FOR_DEPOSIT"}
                """);

        assertThatThrownBy(() -> sut.confirm("pk_abc", "ORD-00000001", 25_800))
                .isInstanceOf(PaymentGatewayUnknownException.class);
    }

    @Test
    void confirm_명확한_거절_코드는_RejectedException이다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.FORBIDDEN, """
                {"code":"REJECT_CARD_COMPANY","message":"카드사에서 거절한 결제입니다."}
                """);

        assertThatThrownBy(() -> sut.confirm("pk_abc", "ORD-00000001", 25_800))
                .isInstanceOf(PaymentGatewayRejectedException.class)
                .hasMessage("카드사에서 거절한 결제입니다.");
    }

    // 10분 confirm 유효시간 초과 — handoff 문서가 명시적으로 거절로 분류하라고 한 케이스.
    @Test
    void confirm_결제_세션_만료는_RejectedException이다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.NOT_FOUND, """
                {"code":"NOT_FOUND_PAYMENT_SESSION","message":"이미 만료된 결제 세션입니다."}
                """);

        assertThatThrownBy(() -> sut.confirm("pk_abc", "ORD-00000001", 25_800))
                .isInstanceOf(PaymentGatewayRejectedException.class);
    }

    /*
     * 화이트리스트에 없는 코드(PROVIDER_ERROR)는 "명확한 거절"이 아니라 기본값인 UNKNOWN으로
     * 떨어진다 — TossPaymentGateway.DEFINITE_REJECT_CODES 주석 참고.
     */
    @Test
    void confirm_화이트리스트에_없는_코드는_UnknownException이다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.BAD_REQUEST, """
                {"code":"PROVIDER_ERROR","message":"일시적인 오류가 발생했습니다."}
                """);

        assertThatThrownBy(() -> sut.confirm("pk_abc", "ORD-00000001", 25_800))
                .isInstanceOf(PaymentGatewayUnknownException.class);
    }

    // 이미 승인됐을 수도 있는 "이미 처리된 결제"를 거절로 단정하지 않는지 확인한다.
    @Test
    void confirm_이미_처리된_결제_코드도_UnknownException이다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.BAD_REQUEST, """
                {"code":"ALREADY_PROCESSED_PAYMENT","message":"이미 처리된 결제입니다."}
                """);

        assertThatThrownBy(() -> sut.confirm("pk_abc", "ORD-00000001", 25_800))
                .isInstanceOf(PaymentGatewayUnknownException.class);
    }

    @Test
    void confirm_5xx는_UnknownException이다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.INTERNAL_SERVER_ERROR, """
                {"code":"FAILED_INTERNAL_SYSTEM_PROCESSING","message":"내부 시스템 오류"}
                """);

        assertThatThrownBy(() -> sut.confirm("pk_abc", "ORD-00000001", 25_800))
                .isInstanceOf(PaymentGatewayUnknownException.class);
    }

    @Test
    void inquire_DONE이면_승인으로_변환한다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.OK, """
                {"paymentKey":"pk_abc","orderId":"ORD-00000001","status":"DONE",
                 "approvedAt":"2026-09-27T10:15:00+09:00","method":"카드"}
                """);

        PaymentGatewayInquiryResult result = sut.inquire("ORD-00000001");

        assertThat(result.status()).isEqualTo(PaymentGatewayInquiryResult.Status.APPROVED);
        assertThat(result.pgTid()).isEqualTo("pk_abc");
    }

    @Test
    void inquire_ABORTED면_실패_사유와_함께_거절로_변환한다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.OK, """
                {"paymentKey":"pk_abc","orderId":"ORD-00000001","status":"ABORTED",
                 "failure":{"code":"REJECT_CARD_COMPANY","message":"카드사 거절"}}
                """);

        PaymentGatewayInquiryResult result = sut.inquire("ORD-00000001");

        assertThat(result.status()).isEqualTo(PaymentGatewayInquiryResult.Status.REJECTED);
        assertThat(result.reason()).isEqualTo("카드사 거절");
    }

    @Test
    void inquire_아직_결론_안_난_상태는_STILL_PROCESSING이다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.OK, """
                {"paymentKey":"pk_abc","orderId":"ORD-00000001","status":"IN_PROGRESS"}
                """);

        PaymentGatewayInquiryResult result = sut.inquire("ORD-00000001");

        assertThat(result.status()).isEqualTo(PaymentGatewayInquiryResult.Status.STILL_PROCESSING);
    }

    @Test
    void inquire_호출_자체가_실패하면_예외가_그대로_전파된다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.INTERNAL_SERVER_ERROR, """
                {"code":"FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING","message":"조회 실패"}
                """);

        assertThatThrownBy(() -> sut.inquire("ORD-00000001"))
                .isInstanceOf(WebClientResponseException.class);
    }

    @Test
    void cancel_성공하면_예외없이_끝난다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.OK, """
                {"paymentKey":"pk_abc","orderId":"ORD-00000001","status":"CANCELED"}
                """);

        // cancel()은 void 계약이라 반환값이 없다 — 예외 없이 끝나는 것 자체가 검증 대상이다.
        // assertThatCode(...).doesNotThrowAnyException()는 의미는 같지만 Sonar의 S2699가
        // assertion으로 인식하지 못해(java:S2699) catchThrowable + isNull()로 대신 쓴다.
        Throwable thrown = catchThrowable(() -> sut.cancel("pk_abc", "이미 취소된 주문에 뒤늦게 결제가 승인됨"));

        assertThat(thrown).isNull();
    }

    /*
     * 이벤트 재전달 등으로 같은 취소가 중복 호출된 경우, 토스가 ALREADY_CANCELED_PAYMENT로 거절해도
     * 이미 원하는 상태(취소됨)에 도달해 있으므로 예외를 던지지 않고 멱등하게 흡수한다
     * (TossPaymentGateway.ALREADY_CANCELED_CODES 주석 참고).
     */
    @Test
    void cancel_이미_취소된_결제는_예외없이_흡수한다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.BAD_REQUEST, """
                {"code":"ALREADY_CANCELED_PAYMENT","message":"이미 취소된 결제입니다."}
                """);

        Throwable thrown = catchThrowable(() -> sut.cancel("pk_abc", "이미 취소된 주문에 뒤늦게 결제가 승인됨"));

        assertThat(thrown).isNull();
    }

    @Test
    void cancel_그_외_실패는_UnknownException이다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.BAD_REQUEST, """
                {"code":"NOT_CANCELABLE_AMOUNT","message":"취소할 수 없는 금액입니다."}
                """);

        assertThatThrownBy(() -> sut.cancel("pk_abc", "사유"))
                .isInstanceOf(PaymentGatewayUnknownException.class);
    }

    @Test
    void cancel_5xx는_UnknownException이다() {
        TossPaymentGateway sut = gatewayReturning(HttpStatus.INTERNAL_SERVER_ERROR, """
                {"code":"FAILED_INTERNAL_SYSTEM_PROCESSING","message":"내부 시스템 오류"}
                """);

        assertThatThrownBy(() -> sut.cancel("pk_abc", "사유"))
                .isInstanceOf(PaymentGatewayUnknownException.class);
    }

    private static TossPaymentGateway gatewayReturning(HttpStatus status, String body) {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(fakeExchangeFunction(status, body))
                .build();
        return new TossPaymentGateway(webClient, CLOCK);
    }

    private static org.springframework.web.reactive.function.client.ExchangeFunction fakeExchangeFunction(
            HttpStatus status, String body) {
        return (ClientRequest request) -> Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }
}
