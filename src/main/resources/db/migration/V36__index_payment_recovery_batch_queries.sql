-- 결제 복구 배치와 결제 대기 주문 만료 배치는 모두 다음 형태로 대상을 커서 순회한다.
--
--   WHERE status = ? AND <pk> > ? AND updated_at < ? ORDER BY <pk> ASC LIMIT 100
--
-- status는 등치 조건, PK는 다음 페이지를 잇는 범위·정렬 조건이다. 따라서 (status, PK)를 앞에
-- 둬야 매 주기마다 이미 해소된 다른 상태 행을 훑지 않고 filesort 없이 커서를 이어갈 수 있다.
-- updated_at은 PK 범위 뒤라 단독 범위 인덱스처럼 탐색 범위를 자르지는 못하지만, 인덱스 조건
-- 푸시다운으로 오래되지 않은 행을 테이블 접근 전에 걸러낼 수 있어 마지막에 둔다.
--
-- (status, updated_at, PK) 순서는 updated_at 범위 뒤 ORDER BY PK를 만족하지 못해 이 커서 쿼리와
-- 맞지 않는다. 실제 운영 데이터 분포가 생기면 EXPLAIN ANALYZE로 rows/filtered를 확인한다.

CREATE INDEX idx_orders_payment_pending_cursor
    ON orders (status, order_id, updated_at);

CREATE INDEX idx_payment_reconciliation_cursor
    ON payment (status, payment_id, updated_at);
