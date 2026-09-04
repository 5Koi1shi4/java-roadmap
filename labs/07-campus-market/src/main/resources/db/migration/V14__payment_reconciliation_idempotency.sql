ALTER TABLE payment_order
    ADD COLUMN request_hash BINARY(32),
    ADD COLUMN response_utf8 LONGTEXT,
    DROP CHECK ck_payment_order_status,
    ADD CONSTRAINT ck_payment_order_status CHECK (status IN ('CREATED', 'PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'CANCELLED'));

ALTER TABLE refund_order
    ADD COLUMN request_hash BINARY(32),
    ADD COLUMN response_utf8 LONGTEXT,
    DROP CHECK ck_refund_order_status,
    ADD CONSTRAINT ck_refund_order_status CHECK (status IN ('REQUESTED', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'CANCELLED'));
