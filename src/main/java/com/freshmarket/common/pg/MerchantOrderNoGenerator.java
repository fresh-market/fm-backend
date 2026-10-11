package com.freshmarket.common.pg;

/*
 * PG(결제대행사)에 보내는 가맹점 주문번호를 orderId로부터 만든다.
 *
 * order와 payment는 둘 다 도메인 계층상 L2라 서로의 도메인 타입을 가져다 쓸 수 없다
 * (ArchitectureTest.도메인은_아래로만_부른다, OrderPaymentRequestedEvent 클래스 주석 참고).
 * 그런데 이 값은 두 도메인 모두 같은 시점에 필요하다 — order는 주문 생성 응답(프론트가 토스
 * 결제창에 넘길 값)에 담아야 하고, payment는 자신의 Payment 행(pg_order_no)에 저장해야 한다.
 * order가 주문을 만든 직후에는 아직 Payment 행이 없어(결제 준비는 이벤트로 뒤이어 일어난다)
 * order가 payment에게 이 값을 물어볼 수도 없다. common은 두 규칙 모두에서 예외로 빠져 있어
 * (OrderPaymentRequestedEvent 클래스 주석 참고) 여기 두면 양쪽이 서로 몰라도 각자 같은 값을
 * 얻는다.
 *
 * 순수 함수다 — orderId 하나로 항상 같은 값이 나온다. 토스 orderId 규칙(6~64자, 영문/숫자/-/_)을
 * 만족한다: orders.order_no는 정책상 orderId를 문자열로 그대로 담아(Order.assignOrderNo 클래스
 * 주석 참고) 초기 주문은 6자에 못 미칠 수 있어 그대로 쓰지 않는다.
 *
 * payment는 이 값을 매번 다시 계산하지 않고 생성 시점에 고정해서 저장한다(Payment.pgOrderNo
 * 필드 주석 참고) — 나중에 이 규칙이 바뀌어도 이미 PG에 보낸 기존 값은 그대로 남아야 하기 때문이다.
 */
public final class MerchantOrderNoGenerator {

    private static final String PREFIX = "ORD-";
    private static final int DIGITS = 8;

    private MerchantOrderNoGenerator() {
    }

    public static String from(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId 는 필수다");
        }
        return PREFIX + String.format("%0" + DIGITS + "d", orderId);
    }
}
