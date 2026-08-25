-- (API-5-07/AIP-155) 이미지 업로드 URL 발급(createUploadUrl) 재시도 감지. stock_lot.request_id와
-- 같은 목적이다 — 클라이언트가 타임아웃 등으로 같은 발급 요청을 다시 보내도 새 PENDING 행과
-- 새 서명 URL이 계속 쌓이지 않게, 요청 식별자로 이미 처리된 요청인지 먼저 확인한다.
-- ProductImage.register()가 유일한 생성 경로라 NOT NULL로 둔다(stock_movement.request_id처럼
-- 값이 없는 것이 정상인 다른 생성 경로가 없다).
ALTER TABLE product_image
    ADD COLUMN request_id VARCHAR(100) NOT NULL AFTER product_id,
    ADD CONSTRAINT uk_product_image_request_id UNIQUE (request_id);
