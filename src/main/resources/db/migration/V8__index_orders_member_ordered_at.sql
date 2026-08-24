-- 회원 주문 목록은 member_id로 범위를 좁힌 뒤 주문 시각/ID 내림차순으로 페이지를 만든다.
-- order_id는 ordered_at이 같은 행에서도 페이지 순서를 고정하는 tie-breaker다.
CREATE INDEX idx_orders_member_ordered_at_order_id
    ON orders (member_id, ordered_at DESC, order_id DESC);
