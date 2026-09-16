/* Task 12 review hardening: the platform-only seven-day term is a persisted value,
   and command fingerprints make replay/conflict handling explicit. */
ALTER TABLE warranty_case
    DROP CHECK ck_warranty_days,
    ADD CONSTRAINT ck_warranty_days CHECK (warranty_days IN (7, 30, 90, 180, 365)),
    ADD COLUMN request_hash BINARY(32) NULL AFTER idempotency_key,
    ADD COLUMN decision_idempotency_key VARCHAR(191) NULL AFTER decision,
    ADD COLUMN decision_request_hash BINARY(32) NULL AFTER decision_idempotency_key,
    ADD KEY idx_warranty_case_decision_key (id, decision_idempotency_key);

ALTER TABLE seller_obligation
    ADD KEY idx_seller_obligation_funding_seller (seller_id, status, funding_deadline, id);

CREATE TABLE seller_obligation_funding (
    id CHAR(36) NOT NULL,
    obligation_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(191) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    amount_fen BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_obligation_funding_key (obligation_id, idempotency_key),
    CONSTRAINT ck_obligation_funding_amount CHECK (amount_fen > 0),
    CONSTRAINT fk_obligation_funding_obligation FOREIGN KEY (obligation_id) REFERENCES seller_obligation (id)
);
