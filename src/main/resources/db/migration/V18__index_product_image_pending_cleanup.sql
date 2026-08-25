-- PendingProductImageCleanupService(정리 배치)의 대상 조회(findByUploadStatusAndIdGreaterThanAnd
-- CreatedAtBeforeOrderByIdAsc)는 upload_status='PENDING'이고 created_at이 유예 시간보다 오래된
-- 행을 훑는다. 인덱스가 없으면 확정된 행이 쌓일수록 주기마다 테이블 전체를 스캔하게 된다(INF-11-13).
CREATE INDEX idx_product_image_pending_cleanup
    ON product_image (upload_status, created_at);
