package com.freshmarket.payment.internal.client;

import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayRejectedException;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayUnknownException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/*
 * [2026-09-27 KST] 실제 토스페이먼츠 API를 부르는 PaymentGateway 구현체.
 *
 * MockPaymentGateway의 클래스 주석에 있던 경고("운영도 지금은 이 Mock을 쓴다") 때문에, 이 클래스에
 * @Profile("prod")를 거는 것과 MockPaymentGateway의 @Profile({"local","prod"})에서 "prod"를 빼는
 * 것을 같은 커밋에서 함께 한다 — 둘을 나누면 배포 중간에 PaymentGateway 빈이 0개인 순간이 생겨
 * #203과 같은 방식으로 배포가 실패한다.
 *
 * WebClient는 WebClientConfig.tossPaymentWebClient()가 만든 빈만 쓴다 — baseUrl/Basic 인증이
 * 이미 그 빈에 걸려 있어 여기서는 경로와 바디만 신경 쓰면 된다
 * (ArchitectureTest.WebClient는_직접_생성하지_않는다).
 *
 * client 패키지는 @Transactional을 걸 수 없다(ArchitectureTest.client_에_트랜잭션이_없다) — PG
 * 호출은 항상 호출하는 쪽(PaymentConfirmationService/PaymentReconciliationService/
 * PaymentCancellationService)이 트랜잭션 밖에서 부른다는 전제를 이 클래스도 그대로 따른다.
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class TossPaymentGateway implements PaymentGateway {

    /*
     * [2026-09-27 KST] docs.tosspayments.com/reference/error-codes 기준 "명확한 거절"만 담은
     * 허용목록이다. 여기 없는 코드(PROVIDER_ERROR/ALREADY_PROCESSED_PAYMENT/UNAUTHORIZED_KEY/5xx
     * 전부/향후 토스가 새로 추가하는 코드 등)는 전부 기본값인 UNKNOWN으로 떨어진다 — "모르는 실패를
     * 거절로 단정하지 않는다"는 이 프로젝트의 원칙(PaymentGateway 인터페이스 주석 참고)을 코드
     * 목록이 아니라 분류 방향 자체로 강제하기 위해서다. 화이트리스트라 새 코드가 추가돼도 안전한
     * 쪽(재확인)으로 기본 동작한다 — 반대로 블랙리스트였다면 새 코드를 놓쳤을 때 진짜 거절을
     * 재확인 대상으로만 계속 남겨두는 정도의 실수라 위험 방향이 다르다.
     *
     * ALREADY_PROCESSED_PAYMENT를 일부러 뺐다 — "이미 처리됨"이 실제로는 이전 시도가 승인까지
     * 갔다는 뜻일 수 있어서, 이걸 거절로 단정하면 이미 결제된 주문을 실패 처리해버릴 위험이 있다.
     * 대신 UNKNOWN으로 떨어뜨려 복구 배치의 inquire()가 실제 상태를 확인하게 한다.
     *
     * NOT_FOUND_PAYMENT_SESSION은 문서(handoff)에서 이미 거절로 명시했다 — confirm 유효시간(10분)
     * 초과는 재시도해도 절대 성공할 수 없는 확정된 거절이다.
     */
    private static final Set<String> DEFINITE_REJECT_CODES = Set.of(
            "REJECT_CARD_COMPANY", "REJECT_ACCOUNT_PAYMENT", "REJECT_CARD_PAYMENT",
            "REJECT_TOSSPAY_INVALID_ACCOUNT",
            "INVALID_REJECT_CARD", "INVALID_CARD_EXPIRATION", "INVALID_STOPPED_CARD",
            "INVALID_CARD_LOST_OR_STOLEN", "INVALID_CARD_NUMBER", "INVALID_PASSWORD",
            "INVALID_ACCOUNT_INFO_RE_REGISTER", "INVALID_UNREGISTERED_SUBMALL", "RESTRICTED_TRANSFER_ACCOUNT",
            "EXCEED_MAX_DAILY_PAYMENT_COUNT", "EXCEED_MAX_PAYMENT_AMOUNT", "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT",
            "EXCEED_MAX_AMOUNT", "EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT", "EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT",
            "EXCEED_MAX_ONE_DAY_AMOUNT", "EXCEED_MAX_AUTH_COUNT",
            "NOT_ALLOWED_POINT_USE", "BELOW_MINIMUM_AMOUNT",
            "NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT", "NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN",
            "INVALID_CARD_INSTALLMENT_PLAN", "EXCEED_MAX_CARD_INSTALLMENT_PLAN",
            "NOT_AVAILABLE_PAYMENT", "NOT_AVAILABLE_BANK",
            "UNAPPROVED_ORDER_ID", "NOT_REGISTERED_BUSINESS", "FDS_ERROR",
            "NOT_FOUND_PAYMENT_SESSION", "NOT_FOUND_PAYMENT");

    /*
     * [2026-09-27 KST] cancel() 전용 허용목록이다. DEFINITE_REJECT_CODES와 성격이 다르다 — 저건
     * "거절로 단정해도 되는 코드", 이건 "이미 취소가 끝나 있다는 뜻이라 성공으로 흡수해도 되는
     * 코드"다. 이벤트 재전달로 같은 취소 요청이 중복 호출될 수 있는데(OrderPaymentRefundRequestedEvent
     * 참고), 토스는 이미 취소된 결제를 다시 취소하면 이 코드로 거절한다 — 호출하는 쪽 입장에서는
     * "이미 원하는 상태(취소됨)에 도달해 있다"는 뜻이라 예외를 던지지 않고 조용히 성공 처리한다.
     */
    private static final Set<String> ALREADY_CANCELED_CODES = Set.of("ALREADY_CANCELED_PAYMENT");

    // 승인 성공(status=DONE) 응답의 approvedAt이 이 값이 아니면 방어적으로 UNKNOWN 처리한다.
    private static final String STATUS_DONE = "DONE";
    private static final Set<String> INQUIRY_APPROVED_STATUSES = Set.of("DONE");
    private static final Set<String> INQUIRY_REJECTED_STATUSES = Set.of("ABORTED", "EXPIRED", "CANCELED", "PARTIAL_CANCELED");

    private final WebClient tossPaymentWebClient;
    private final Clock clock;

    @Override
    public PaymentGatewayApproval confirm(String paymentKey, String pgOrderNo, int amount) {
        TossPaymentResponse response;
        try {
            response = tossPaymentWebClient.post()
                    .uri("/v1/payments/confirm")
                    // [2026-09-27 KST] 같은 confirm 시도가 네트워크 재시도 등으로 중복 전송돼도
                    // 토스가 한 번만 처리하도록 한다. paymentKey는 이 결제 시도 전체에서 고정된
                    // 값이라(프론트가 재시도해도 같은 paymentKey를 다시 보낸다) 멱등키로 그대로
                    // 쓸 수 있다 — 토스 문서도 "UUID 같은 충분히 무작위적인 고유 값"을 예시로 들 뿐
                    // UUID 형식을 강제하지 않는다.
                    .header("Idempotency-Key", paymentKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new TossConfirmRequest(paymentKey, pgOrderNo, amount))
                    .retrieve()
                    .bodyToMono(TossPaymentResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw toConfirmException(e);
        } catch (RuntimeException e) {
            // 연결 자체가 안 되거나(WebClientRequestException) 응답 자체가 없는 경우
            // (WebClientConfig의 connect/response 타임아웃) — 승인됐는지 거절됐는지 전혀 알 수 없다.
            throw new PaymentGatewayUnknownException("토스 confirm 호출 실패: " + e.getMessage(), e);
        }

        if (response == null || !STATUS_DONE.equals(response.status())) {
            // 이 프로젝트가 프론트에 노출하는 결제수단(카드/간편결제)은 confirm 성공 시 바로
            // DONE이어야 정상이다. 가상계좌처럼 발급만 되고 입금을 기다리는 수단이 섞여 들어오면
            // (프론트 설정 실수 등) 여기서 승인으로 단정하지 않고 UNKNOWN으로 넘겨 복구 배치가
            // inquire()로 실제 상태를 확인하게 한다.
            String actualStatus = response == null ? "null" : response.status();
            log.warn("event=TOSS_CONFIRM_UNEXPECTED_STATUS pgOrderNo={} status={}", pgOrderNo, actualStatus);
            throw new PaymentGatewayUnknownException("토스 confirm 응답 status가 DONE이 아님: " + actualStatus, null);
        }

        return new PaymentGatewayApproval(response.paymentKey(), toLocalDateTime(response.approvedAt()),
                mapMethod(response.method()));
    }

    private RuntimeException toConfirmException(WebClientResponseException e) {
        TossErrorResponse error = readErrorBody(e);
        String code = error == null ? null : error.code();
        String message = error == null ? e.getMessage() : error.message();
        if (code != null && DEFINITE_REJECT_CODES.contains(code)) {
            log.info("event=TOSS_CONFIRM_REJECTED code={} message={}", code, message);
            return new PaymentGatewayRejectedException(message);
        }
        log.warn("event=TOSS_CONFIRM_UNKNOWN_FAILURE status={} code={} message={}",
                e.getStatusCode(), code, message, e);
        return new PaymentGatewayUnknownException("토스 confirm 실패(code=" + code + "): " + message, e);
    }

    /*
     * [2026-09-05 18:28 KST] 복구 배치가 UNKNOWN/PENDING 결제의 실제 PG 측 결과를 재확인할 때
     * 부른다. 여기서 던지는 예외는 confirm()과 달리 REJECTED/UNKNOWN을 가르지 않는다 —
     * PaymentReconciliationService.reconcileOne이 이미 "이번 조회가 실패하면 다음 주기에
     * 재시도"로 통일해서 처리하므로(INQUIRE_FAILED), inquire 자체가 실패했다는 사실만 전달하면
     * 충분하다.
     */
    @Override
    public PaymentGatewayInquiryResult inquire(String pgOrderNo) {
        TossPaymentResponse response;
        try {
            response = tossPaymentWebClient.get()
                    .uri("/v1/payments/orders/{orderId}", pgOrderNo)
                    .retrieve()
                    .bodyToMono(TossPaymentResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("event=TOSS_INQUIRE_FAILED pgOrderNo={} status={} body={}",
                    pgOrderNo, e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw e;
        }

        if (response == null) {
            throw new IllegalStateException("토스 inquire 응답이 비어 있음: pgOrderNo=" + pgOrderNo);
        }

        String status = response.status();
        if (INQUIRY_APPROVED_STATUSES.contains(status)) {
            return PaymentGatewayInquiryResult.approved(response.paymentKey(), toLocalDateTime(response.approvedAt()),
                    mapMethod(response.method()));
        }
        if (INQUIRY_REJECTED_STATUSES.contains(status)) {
            String reason = response.failure() != null ? response.failure().message() : status;
            return PaymentGatewayInquiryResult.rejected(reason);
        }
        // WAITING_FOR_DEPOSIT(가상계좌 입금 대기)/IN_PROGRESS 등, 그리고 알려지지 않은 새 상태값도
        // 안전하게 "아직 결론 안 남"으로 다룬다 — 여기서 섣불리 승인/거절로 단정하지 않는다.
        log.info("event=TOSS_INQUIRE_STILL_PROCESSING pgOrderNo={} status={}", pgOrderNo, status);
        return PaymentGatewayInquiryResult.stillProcessing();
    }

    /*
     * [2026-09-27 KST] order가 이미 취소된 주문에 뒤늦게 결제가 승인된 경우의 자동 환불에 쓴다
     * (PaymentGateway.cancel() 주석 참고). 응답 바디는 취소 후 결제 상세를 돌려주지만, 이 메서드는
     * "성공했는가"만 관심 대상이라 파싱하지 않는다(TossPaymentResponse.class로 역직렬화만 시도해
     * 응답 자체는 소비한다 — 실패해도 이 메서드 결과에 영향 없다).
     */
    @Override
    public void cancel(String paymentKey, String reason) {
        try {
            tossPaymentWebClient.post()
                    .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                    // confirm()과 같은 이유로 멱등키를 건다. paymentKey는 confirm의 Idempotency-Key와
                    // 이미 겹치는 값이라, 접두사로 네임스페이스를 나눠 confirm 재시도와 섞이지 않게 한다.
                    .header("Idempotency-Key", "cancel:" + paymentKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new TossCancelRequest(reason))
                    .retrieve()
                    .bodyToMono(TossPaymentResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            TossErrorResponse error = readErrorBody(e);
            String code = error == null ? null : error.code();
            String message = error == null ? e.getMessage() : error.message();
            if (code != null && ALREADY_CANCELED_CODES.contains(code)) {
                // 이벤트 재전달 등으로 같은 취소가 중복 호출된 경우다 — 이미 원하는 상태(취소됨)에
                // 도달해 있으므로 멱등하게 흡수한다. Payment.cancel()도 이미 CANCELED면 조용히
                // 넘어가므로 이중으로 안전하다.
                log.info("event=TOSS_CANCEL_ALREADY_DONE paymentKey={} code={}", paymentKey, code);
                return;
            }
            log.warn("event=TOSS_CANCEL_FAILED paymentKey={} status={} code={} message={}",
                    e.getStatusCode(), code, message, e);
            throw new PaymentGatewayUnknownException("토스 cancel 실패(code=" + code + "): " + message, e);
        } catch (RuntimeException e) {
            // 연결 자체가 안 되거나 응답 자체가 없는 경우 — 취소됐는지 여부를 전혀 알 수 없다. 여기서
            // 삼키지 않고 그대로 예외를 던져 호출하는 쪽(PaymentCancellationService)이 트랜잭션을
            // 확정하지 않게 한다 — 다음 이벤트 재전달이 다시 시도한다.
            throw new PaymentGatewayUnknownException("토스 cancel 호출 실패: " + e.getMessage(), e);
        }
    }

    private TossErrorResponse readErrorBody(WebClientResponseException e) {
        try {
            return e.getResponseBodyAs(TossErrorResponse.class);
        } catch (RuntimeException parseFailure) {
            log.warn("event=TOSS_ERROR_BODY_PARSE_FAILED status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), parseFailure);
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(String isoOffsetDateTime) {
        return OffsetDateTime.parse(isoOffsetDateTime).atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    /*
     * payment.method는 CARD/EASY_PAY 두 값만 허용한다(PaymentMethod 주석 참고). 토스 결제창은
     * 프론트가 이 두 수단만 선택 가능하도록 이미 제한해뒀다는 전제라, "카드"가 아니면 전부
     * EASY_PAY로 묶는다 — 언젠가 결제수단이 더 늘어나 이 전제가 깨지면(가상계좌 등) PaymentMethod
     * enum 자체를 넓히는 별도 작업이 필요하다.
     */
    private PaymentMethod mapMethod(String tossMethod) {
        if ("카드".equals(tossMethod)) {
            return PaymentMethod.CARD;
        }
        if (tossMethod != null && !tossMethod.isBlank()) {
            return PaymentMethod.EASY_PAY;
        }
        log.warn("event=TOSS_METHOD_MISSING_DEFAULTED_TO_EASY_PAY");
        return PaymentMethod.EASY_PAY;
    }
}
