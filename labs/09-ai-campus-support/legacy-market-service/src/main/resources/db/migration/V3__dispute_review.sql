CREATE TABLE handoff_record (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    actor_id CHAR(36) NOT NULL,
    note VARCHAR(500),
    handed_off_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_handoff_record_order (order_id),
    CONSTRAINT fk_handoff_record_order FOREIGN KEY (order_id) REFERENCES trade_order (id)
);

CREATE TABLE dispute_case (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    initiator_id CHAR(36) NOT NULL,
    disputed_quantity INT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    seller_deadline TIMESTAMP(6),
    admin_deadline TIMESTAMP(6),
    decision VARCHAR(40),
    approved_quantity INT,
    version BIGINT NOT NULL DEFAULT 0,
    opened_at TIMESTAMP(6) NOT NULL,
    resolved_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_dispute_case_order (order_id, created_at),
    CONSTRAINT ck_dispute_case_quantity CHECK (disputed_quantity > 0),
    CONSTRAINT ck_dispute_case_approved_quantity CHECK (approved_quantity IS NULL OR approved_quantity > 0),
    CONSTRAINT ck_dispute_case_status CHECK (status IN ('OPEN', 'SELLER_RESPONDED', 'UNDER_REVIEW', 'ESCALATED', 'RESOLVED', 'REJECTED')),
    CONSTRAINT ck_dispute_case_version CHECK (version >= 0),
    CONSTRAINT fk_dispute_case_order FOREIGN KEY (order_id) REFERENCES trade_order (id)
);

CREATE TABLE dispute_evidence (
    id CHAR(36) NOT NULL,
    dispute_case_id CHAR(36),
    warranty_case_id CHAR(36),
    case_type VARCHAR(20) NOT NULL,
    submitted_by CHAR(36) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dispute_evidence_object_key (object_key),
    CONSTRAINT ck_dispute_evidence_case CHECK ((case_type = 'DISPUTE' AND dispute_case_id IS NOT NULL AND warranty_case_id IS NULL)
        OR (case_type = 'WARRANTY' AND dispute_case_id IS NULL AND warranty_case_id IS NOT NULL)),
    CONSTRAINT ck_dispute_evidence_size CHECK (size_bytes >= 0),
    CONSTRAINT fk_dispute_evidence_dispute FOREIGN KEY (dispute_case_id) REFERENCES dispute_case (id)
);

CREATE TABLE return_case (
    id CHAR(36) NOT NULL,
    dispute_case_id CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    proof_type VARCHAR(40),
    proof_reference VARCHAR(500),
    confirmed_by CHAR(36),
    approved_quantity INT NOT NULL,
    deadline TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_return_case_dispute (dispute_case_id),
    CONSTRAINT ck_return_case_quantity CHECK (approved_quantity > 0),
    CONSTRAINT ck_return_case_status CHECK (status IN ('REQUESTED', 'AWAITING_PROOF', 'CONFIRMED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT fk_return_case_dispute FOREIGN KEY (dispute_case_id) REFERENCES dispute_case (id)
);

CREATE TABLE warranty_case (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(191) NOT NULL,
    buyer_id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    warranty_days INT NOT NULL,
    warranty_scope_snapshot TEXT NOT NULL,
    manufacturer_warranty_proof_snapshot TEXT,
    manufacturer_warranty_expires_at TIMESTAMP(6),
    disputed_quantity INT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    seller_deadline TIMESTAMP(6) NOT NULL,
    admin_deadline TIMESTAMP(6),
    decision VARCHAR(40),
    compensation_amount_fen BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    opened_at TIMESTAMP(6) NOT NULL,
    closed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_warranty_case_order_idempotency (order_id, idempotency_key),
    CONSTRAINT ck_warranty_days CHECK (warranty_days IN (30, 90, 180, 365)),
    CONSTRAINT ck_warranty_case_quantity CHECK (disputed_quantity > 0),
    CONSTRAINT ck_warranty_case_compensation CHECK (compensation_amount_fen >= 0),
    CONSTRAINT ck_warranty_case_status CHECK (status IN ('OPEN', 'SELLER_RESPONDED', 'UNDER_REVIEW', 'ESCALATED', 'RESOLVED', 'REJECTED')),
    CONSTRAINT ck_warranty_case_version CHECK (version >= 0),
    CONSTRAINT fk_warranty_case_order FOREIGN KEY (order_id) REFERENCES trade_order (id)
);

ALTER TABLE dispute_evidence
    ADD CONSTRAINT fk_dispute_evidence_warranty FOREIGN KEY (warranty_case_id) REFERENCES warranty_case (id);

ALTER TABLE inventory_movement
    ADD CONSTRAINT fk_inventory_movement_dispute FOREIGN KEY (dispute_case_id) REFERENCES dispute_case (id);

CREATE TABLE seller_obligation (
    id CHAR(36) NOT NULL,
    warranty_case_id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    obligation_business_key VARCHAR(191) NOT NULL,
    obligation_amount_fen BIGINT NOT NULL,
    funded_amount_fen BIGINT NOT NULL DEFAULT 0,
    funding_deadline TIMESTAMP(6) NOT NULL,
    future_settlement_deduction_key VARCHAR(191),
    restriction_status VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seller_obligation_warranty_case (warranty_case_id),
    UNIQUE KEY uk_seller_obligation_business_key (obligation_business_key),
    UNIQUE KEY uk_seller_obligation_deduction_key (future_settlement_deduction_key),
    KEY idx_seller_obligation_funding (status, funding_deadline),
    CONSTRAINT ck_obligation_amount CHECK (obligation_amount_fen >= 0),
    CONSTRAINT ck_obligation_funded CHECK (funded_amount_fen >= 0 AND funded_amount_fen <= obligation_amount_fen),
    CONSTRAINT ck_seller_obligation_status CHECK (status IN ('AWAITING_FUNDING', 'PARTIALLY_FUNDED', 'FUNDED', 'CANCELLED')),
    CONSTRAINT ck_seller_obligation_restriction CHECK (restriction_status IN ('NONE', 'RESTRICTED')),
    CONSTRAINT ck_seller_obligation_version CHECK (version >= 0),
    CONSTRAINT fk_seller_obligation_warranty_case FOREIGN KEY (warranty_case_id) REFERENCES warranty_case (id)
);
