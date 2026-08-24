-- (DI-6-01) 로트 입고 성공 뒤 옵션 품절 여부 갱신이 별도 AFTER_COMMIT 이벤트 리스너로 분리되는데,
-- 그 리스너의 반영이 실패하면 product_option.sold_out이 실제 재고와 어긋난 채로 영구히 남을 수
-- 있다. 이 표가 그 실패를 남겨두는 아웃박스 큐다(kakao_unlink_failure와 같은 구조) —
-- OptionAvailabilitySyncScheduler가 주기적으로 재시도하고, 성공하면 행을 지운다.
CREATE TABLE option_availability_sync_failure (
    option_availability_sync_failure_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_option_id BIGINT NOT NULL,
    sold_out BOOLEAN NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_option_availability_sync_failure_option UNIQUE (product_option_id),
    CONSTRAINT fk_option_availability_sync_failure_option FOREIGN KEY (product_option_id)
        REFERENCES product_option (product_option_id)
);
