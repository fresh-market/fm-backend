-- (DI-2-01) product_option.sold_out을 "발생 시각이 더 늦은 이벤트만 반영"하는 조건부 UPDATE로
-- 갱신하기 위한 비교 기준 컬럼. NULL이면 아직 한 번도 이벤트로 갱신된 적이 없다는 뜻이라 무조건
-- 반영한다(V10의 초기 백필값을 덮어써도 안전 — 그 값도 실제 재고 기준으로 계산된 값이었다).
ALTER TABLE product_option
    ADD COLUMN sold_out_synced_at DATETIME(6) NULL AFTER sold_out;
