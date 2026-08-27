-- AdminLotExpireChunkService의 만료 배치(StockLotRepository.findByStatusAndExpiryDateBefore)는
-- status/expiry_date로만 훑는다. idx_lot_fefo(product_option_id, status, expiry_date)는 맨 앞이
-- product_option_id라 이 조회엔 못 타(옵션 필터가 없어 접두사가 안 맞는다) — 인덱스가 없으면
-- stock_lot 전체를 풀스캔한다.
CREATE INDEX idx_lot_status_expiry ON stock_lot (status, expiry_date);
