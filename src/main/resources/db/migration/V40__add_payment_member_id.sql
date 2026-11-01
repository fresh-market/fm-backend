/*
 * 결제 확정(confirm) API가 요청자 본인 주문인지 확인할 때 쓴다. order와 payment 둘 다 L2라
 * confirm 시점에 payment가 order를 다시 조회해 소유자를 확인할 수 없다
 * (ArchitectureTest.도메인은_아래로만_부른다) — 그래서 결제 준비 시점에 order가 이미 아는
 * memberId를 이벤트로 함께 실어 보내 이 컬럼에 스냅샷해둔다. amount를 orders.total_amount에서
 * 스냅샷하는 것과 같은 방식이다(V1의 fk_payment_order 주석 참고).
 *
 * 기존 결제 행이 있을 수 있어 확장(nullable 추가) -> 백필(orders 조인) -> 축소(NOT NULL) 순서로
 * 나눈다(V16/V7/V39 참고).
 */
ALTER TABLE payment
    ADD COLUMN member_id BIGINT NULL AFTER order_id;

UPDATE payment p
JOIN orders o ON o.order_id = p.order_id
SET p.member_id = o.member_id
WHERE p.member_id IS NULL;

ALTER TABLE payment
    MODIFY COLUMN member_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_payment_member FOREIGN KEY (member_id) REFERENCES member (member_id);

CREATE INDEX idx_payment_member ON payment (member_id);
