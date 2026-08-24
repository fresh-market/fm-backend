/*
 * API-5-07(AIP-155) 대응 요청 식별자. 확장(nullable 추가 후 백필) 후 축소(NOT NULL 강제)로
 * 나누는 게 일반적으로 안전하지만, stock_lot은 이번에 처음 생긴 테이블이라 기존 행이 없어서
 * 한 번에 NOT NULL로 추가해도 안전하다.
 * 운영 데이터가 쌓인 뒤 비슷한 컬럼을 추가할 땐 2단계로 나눠야 한다.
 */
ALTER TABLE stock_lot
    ADD COLUMN request_id VARCHAR(100) NOT NULL AFTER product_option_id,
    ADD CONSTRAINT uk_lot_request_id UNIQUE (request_id);
