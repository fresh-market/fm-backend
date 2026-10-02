/*
 * 토스페이먼츠 confirm/조회 API에 보낼 가맹점 주문번호(merchant orderId)다. 토스 orderId는
 * 6~64자 영문/숫자/-/_ 만 허용한다.
 *
 * orders.order_no는 정책상 "orderNo = orderId" 값을 문자열로 그대로 담는다
 * (Order.assignOrderNo 클래스 주석 참고) — order_id가 아직 작은 초기 주문들은 "1", "42"처럼
 * 6자에 못 미쳐 토스 orderId 최소 길이 요건을 만족하지 못한다. 그래서 orders.order_no를 그대로
 * 재사용하지 않고, "ORD-" + 8자리 0패딩 값을 직접 만들어 이 컬럼에 저장한다
 * (common.pg.MerchantOrderNoGenerator 참고) — order도 같은 계산을 독립적으로 할 수 있어 order
 * 도메인은 이 컬럼의 존재조차 몰라도 된다.
 *
 * 한 번 PG에 보낸 값은 이후 재조회·재승인에도 계속 같아야 하므로, 필요할 때마다 다시 계산하지
 * 않고 생성 시점에 고정해서 저장한다 — 나중에 생성 규칙이 바뀌어도 이미 PG에 보낸 기존 값은
 * 그대로 남는다.
 *
 * 기존 결제 행이 있을 수 있어(운영은 MockPaymentGateway가 전부 승인해 실제 PAID 행이 쌓여 있다)
 * 확장(nullable 추가) -> 백필(같은 규칙으로 역산) -> 축소(NOT NULL) 순서로 나눈다(V16/V7 참고).
 */
ALTER TABLE payment
    ADD COLUMN pg_order_no VARCHAR(20) NULL AFTER order_id;

UPDATE payment
SET pg_order_no = CONCAT('ORD-', LPAD(order_id, 8, '0'))
WHERE pg_order_no IS NULL;

ALTER TABLE payment
    MODIFY COLUMN pg_order_no VARCHAR(20) NOT NULL,
    ADD CONSTRAINT uk_payment_pg_order_no UNIQUE (pg_order_no);
