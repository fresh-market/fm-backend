CREATE TABLE order_payment_request_outbox (
    order_payment_request_outbox_id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    amount INT NOT NULL,
    dispatched BOOLEAN NOT NULL DEFAULT FALSE,
    dispatched_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_payment_request_outbox_id),
    UNIQUE KEY uk_opro_order (order_id),
    KEY idx_opro_pending (dispatched, order_payment_request_outbox_id),
    CONSTRAINT fk_opro_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT chk_opro_amount CHECK (amount > 0),
    CONSTRAINT chk_opro_dispatch_time CHECK (
        (dispatched = FALSE AND dispatched_at IS NULL)
        OR (dispatched = TRUE AND dispatched_at IS NOT NULL)
    )
);

CREATE TABLE payment_result_outbox (
    payment_result_outbox_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    paid_at DATETIME(6) NULL,
    failure_reason VARCHAR(500) NULL,
    dispatched BOOLEAN NOT NULL DEFAULT FALSE,
    dispatched_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (payment_result_outbox_id),
    UNIQUE KEY uk_pro_payment (payment_id),
    KEY idx_pro_pending (dispatched, payment_result_outbox_id),
    CONSTRAINT fk_pro_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT fk_pro_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT chk_pro_event_type CHECK (event_type IN ('APPROVED', 'FAILED')),
    CONSTRAINT chk_pro_payload CHECK (
        (event_type = 'APPROVED' AND paid_at IS NOT NULL AND failure_reason IS NULL)
        OR event_type = 'FAILED'
    ),
    CONSTRAINT chk_pro_dispatch_time CHECK (
        (dispatched = FALSE AND dispatched_at IS NULL)
        OR (dispatched = TRUE AND dispatched_at IS NOT NULL)
    )
);
