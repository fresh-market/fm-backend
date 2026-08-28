-- 주문 생성 재시도 식별자. 같은 request_id는 하나의 주문만 만들도록 DB가 최종 보장한다.
ALTER TABLE orders
    ADD COLUMN request_id VARCHAR(64) NULL AFTER order_no,
    ADD COLUMN request_hash CHAR(64) NULL AFTER request_id,
    ADD UNIQUE KEY uk_orders_request_id (request_id);

-- 결제 성공 뒤 cart에서 정확히 구매한 항목만 멱등 삭제하기 위한 내부 추적 값이다.
ALTER TABLE order_item
    ADD COLUMN source_cart_item_id BIGINT NULL AFTER order_id;
