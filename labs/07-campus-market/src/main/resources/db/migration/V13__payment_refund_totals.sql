ALTER TABLE payment_order
    ADD COLUMN successful_refund_fen BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reserved_refund_fen BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_payment_successful_refund CHECK (successful_refund_fen >= 0 AND successful_refund_fen <= paid_amount_fen),
    ADD CONSTRAINT ck_payment_reserved_refund CHECK (reserved_refund_fen >= 0 AND reserved_refund_fen <= paid_amount_fen),
    ADD CONSTRAINT ck_payment_refund_total CHECK (successful_refund_fen + reserved_refund_fen <= paid_amount_fen);
