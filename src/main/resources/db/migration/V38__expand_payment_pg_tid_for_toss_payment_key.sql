-- 토스페이먼츠 paymentKey는 최대 200자다. 기존 PG 식별자와의 호환성을 유지하며 확장만 한다.
ALTER TABLE payment
    MODIFY COLUMN pg_tid VARCHAR(200) NULL;
