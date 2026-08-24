/*
 * 재고 기반 품절 여부. product_option.sale_status(관리자가 수동으로 켜고 끄는 판매 상태)와는
 * 다른 별개 필드다 — product.md 응답에 saleStatus와 soldOut이 각각 따로 노출된다.
 * stock 도메인이 발행하는 OptionAvailabilityChangedEvent를 product가 구독해 갱신한다.
 */
ALTER TABLE product_option
    ADD COLUMN sold_out BOOLEAN NOT NULL DEFAULT true AFTER sale_status;

-- 이 마이그레이션 시점에 이미 로트가 입고된 옵션들의 초기값을 실제 재고 기준으로 백필한다
UPDATE product_option po
SET sold_out = NOT EXISTS (
    SELECT 1 FROM stock_lot sl
    WHERE sl.product_option_id = po.product_option_id
      AND sl.available_qty > 0
);
