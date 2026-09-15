ALTER TABLE payment
    ADD COLUMN reconciliation_attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN reconciliation_isolated BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_payment_reconciliation_active_cursor
    ON payment (status, reconciliation_isolated, payment_id, updated_at);
