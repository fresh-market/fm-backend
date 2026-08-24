-- (API-5-07/AIP-155) 로트 폐기(:dispose) 요청 재시도 감지. register()의 stock_lot.request_id와
-- 같은 목적이다 — 클라이언트가 타임아웃 등으로 같은 폐기 요청을 다시 보내도 중복 차감되지 않게,
-- 요청 식별자로 이미 처리된 요청인지 먼저 확인한다. INBOUND 등 다른 변동 유형은 이 값을 채우지
-- 않는다(NULL) — MySQL UNIQUE는 NULL 여러 개를 서로 다른 값으로 보므로 그대로 둬도 안전하다.
ALTER TABLE stock_movement
    ADD COLUMN request_id VARCHAR(100) NULL AFTER stock_lot_id,
    ADD CONSTRAINT uk_movement_request_id UNIQUE (request_id);
