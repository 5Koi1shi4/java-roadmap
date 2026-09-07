ALTER TABLE warranty_case
    ADD COLUMN assigned_admin_id CHAR(36) NULL AFTER seller_id,
    ADD COLUMN seller_response VARCHAR(2000) NULL AFTER seller_deadline,
    ADD COLUMN hard_deadline TIMESTAMP(6) NULL AFTER admin_deadline,
    ADD COLUMN admin_sla_alerted_at TIMESTAMP(6) NULL,
    ADD KEY idx_warranty_case_deadlines (status, seller_deadline, admin_deadline, hard_deadline),
    ADD CONSTRAINT fk_warranty_case_admin FOREIGN KEY (assigned_admin_id) REFERENCES campus_user (id);

CREATE TABLE warranty_deadline_claim (
    id CHAR(36) NOT NULL,
    warranty_case_id CHAR(36) NOT NULL,
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
    UNIQUE KEY uk_warranty_deadline_case_type (warranty_case_id, deadline_type),
    KEY idx_warranty_deadline_claim (status, due_at, lease_until),
    CONSTRAINT ck_warranty_deadline_type CHECK (deadline_type IN ('SELLER_RESPONSE','ADMIN_SLA','HARD_DEADLINE')),
    CONSTRAINT ck_warranty_deadline_status CHECK (status IN ('NEW','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_warranty_deadline_attempts CHECK (attempt_count >= 0),
    CONSTRAINT fk_warranty_deadline_case FOREIGN KEY (warranty_case_id) REFERENCES warranty_case (id)
);

CREATE TABLE seller_account_restriction (
    seller_id CHAR(36) NOT NULL,
    restriction_type VARCHAR(32) NOT NULL,
    source_obligation_id CHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    cleared_at TIMESTAMP(6),
    PRIMARY KEY (seller_id, restriction_type, source_obligation_id),
    KEY idx_seller_restriction_active (seller_id, restriction_type, status),
    CONSTRAINT ck_seller_restriction_type CHECK (restriction_type IN ('PUBLISH','WITHDRAW')),
    CONSTRAINT ck_seller_restriction_status CHECK (status IN ('ACTIVE','CLEARED')),
    CONSTRAINT fk_seller_restriction_seller FOREIGN KEY (seller_id) REFERENCES campus_user (id),
    CONSTRAINT fk_seller_restriction_obligation FOREIGN KEY (source_obligation_id) REFERENCES seller_obligation (id)
);

CREATE TABLE settlement_obligation_deduction (
    id CHAR(36) NOT NULL,
    settlement_id CHAR(36) NOT NULL,
    obligation_id CHAR(36) NOT NULL,
    amount_fen BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_obligation_deduction (settlement_id, obligation_id),
    KEY idx_settlement_deduction_obligation (obligation_id, created_at),
    CONSTRAINT ck_settlement_deduction_amount CHECK (amount_fen > 0),
    CONSTRAINT fk_settlement_deduction_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (id),
    CONSTRAINT fk_settlement_deduction_obligation FOREIGN KEY (obligation_id) REFERENCES seller_obligation (id)
);
