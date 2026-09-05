ALTER TABLE dispute_case
    ADD COLUMN proof_type VARCHAR(40) NULL AFTER decision,
    ADD COLUMN proof_reference VARCHAR(500) NULL AFTER proof_type,
    ADD COLUMN hard_deadline TIMESTAMP(6) NULL AFTER admin_deadline,
    ADD COLUMN seller_sla_alerted_at TIMESTAMP(6) NULL,
    ADD COLUMN admin_sla_alerted_at TIMESTAMP(6) NULL,
    ADD KEY idx_dispute_case_deadlines (status, seller_deadline, admin_deadline, hard_deadline),
    ADD CONSTRAINT ck_dispute_case_proof_type CHECK (proof_type IS NULL OR proof_type IN ('BUYER_EVIDENCE','SELLER_CONFIRMED','PROVIDER_DELIVERED','ADMIN_CONFIRMED'));

ALTER TABLE return_case
    ADD COLUMN order_id CHAR(36) NULL AFTER dispute_case_id,
    ADD COLUMN listing_id CHAR(36) NULL AFTER order_id,
    ADD COLUMN payment_order_id CHAR(36) NULL AFTER listing_id,
    ADD COLUMN unit_price_fen BIGINT NULL AFTER payment_order_id,
    ADD COLUMN refund_id CHAR(36) NULL AFTER proof_reference,
    ADD COLUMN refund_status VARCHAR(24) NULL AFTER refund_id,
    ADD COLUMN refunded_at TIMESTAMP(6) NULL,
    ADD COLUMN quarantined_at TIMESTAMP(6) NULL,
    ADD KEY idx_return_case_deadline (status, deadline),
    ADD CONSTRAINT ck_return_case_unit_price CHECK (unit_price_fen IS NULL OR unit_price_fen > 0),
    ADD CONSTRAINT ck_return_case_refund_status CHECK (refund_status IS NULL OR refund_status IN ('REQUESTED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED')),
    ADD CONSTRAINT fk_return_case_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    ADD CONSTRAINT fk_return_case_listing FOREIGN KEY (listing_id) REFERENCES listing (id),
    ADD CONSTRAINT fk_return_case_payment FOREIGN KEY (payment_order_id) REFERENCES payment_order (id),
    ADD CONSTRAINT fk_return_case_refund FOREIGN KEY (refund_id) REFERENCES refund_order (id);

CREATE TABLE dispute_deadline_claim (
    id CHAR(36) NOT NULL,
    dispute_case_id CHAR(36) NOT NULL,
    deadline_type VARCHAR(32) NOT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    completed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dispute_deadline_case_type (dispute_case_id, deadline_type),
    KEY idx_dispute_deadline_claim (status, due_at, lease_until),
    CONSTRAINT ck_dispute_deadline_type CHECK (deadline_type IN ('SELLER_RESPONSE','ADMIN_SLA','HARD_DEADLINE')),
    CONSTRAINT ck_dispute_deadline_status CHECK (status IN ('NEW','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_dispute_deadline_attempts CHECK (attempt_count >= 0),
    CONSTRAINT fk_dispute_deadline_case FOREIGN KEY (dispute_case_id) REFERENCES dispute_case (id)
);

ALTER TABLE settlement
    ADD COLUMN blocked_reason VARCHAR(100) NULL,
    ADD COLUMN settled_at TIMESTAMP(6) NULL;
