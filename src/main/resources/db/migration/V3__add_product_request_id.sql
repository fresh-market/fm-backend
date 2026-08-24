-- API-5-07(AIP-155) 대응: 상품 등록 재시도 시 중복 생성을 막기 위한 요청 식별자
ALTER TABLE product
    ADD COLUMN request_id VARCHAR(100) NOT NULL AFTER product_code,
    ADD CONSTRAINT uk_product_request_id UNIQUE (request_id);
