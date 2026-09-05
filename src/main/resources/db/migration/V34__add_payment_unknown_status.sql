ALTER TABLE payment DROP CHECK chk_payment_status;
ALTER TABLE payment DROP CHECK chk_payment_paid_at;
ALTER TABLE payment
    ADD CONSTRAINT chk_payment_status
        CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'UNKNOWN', 'CANCELED', 'REFUNDED')),
    ADD CONSTRAINT chk_payment_paid_at -- 상태와 완료 시각이 따로 놀지 않도록. CANCELED는 결제 전 취소와 결제 후 취소가 모두 정상이라 제외한다
        CHECK ((status IN ('PENDING', 'FAILED', 'UNKNOWN') AND paid_at IS NULL)
            OR (status IN ('PAID', 'REFUNDED') AND paid_at IS NOT NULL)
            OR status = 'CANCELED');
