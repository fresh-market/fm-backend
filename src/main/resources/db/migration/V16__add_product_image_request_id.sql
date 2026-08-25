/*
 * (API-5-07/AIP-155) 이미지 업로드 URL 발급(createUploadUrl) 재시도 감지. stock_lot.request_id와
 * 같은 목적이다 — 클라이언트가 타임아웃 등으로 같은 발급 요청을 다시 보내도 새 PENDING 행과
 * 새 서명 URL이 계속 쌓이지 않게, 요청 식별자로 이미 처리된 요청인지 먼저 확인한다.
 *
 * 확장(nullable 추가 후 백필) 후 축소(NOT NULL 강제)로 나누는 게 일반적으로 안전하다(V7 참고).
 * product_image 테이블 자체는 이미 있지만(V1), ProductImage.register()가 유일한 생성 경로이고
 * 그 경로가 이번 PR 전까지 어디서도 호출되지 않았어서 기존 행이 있을 수 없다 — 한 번에 NOT NULL로
 * 추가해도 안전하다. 이 테이블에 실제 데이터가 쌓인 뒤 비슷한 컬럼을 추가할 땐 2단계로 나눠야 한다.
 */
ALTER TABLE product_image
    ADD COLUMN request_id VARCHAR(100) NOT NULL AFTER product_id,
    ADD CONSTRAINT uk_product_image_request_id UNIQUE (request_id);
