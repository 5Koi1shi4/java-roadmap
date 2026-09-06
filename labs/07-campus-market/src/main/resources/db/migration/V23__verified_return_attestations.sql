CREATE TABLE return_proof_attestation (
    id CHAR(36) NOT NULL,
    proof_reference VARCHAR(500) NOT NULL,
    order_id CHAR(36) NOT NULL,
    provider VARCHAR(100) NOT NULL,
    delivered_quantity INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    verified_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_return_proof_attestation_reference (proof_reference),
    KEY idx_return_proof_attestation_order (order_id, status),
    CONSTRAINT ck_return_proof_attestation_quantity CHECK (delivered_quantity > 0),
    CONSTRAINT ck_return_proof_attestation_status CHECK (status IN ('DELIVERED','REJECTED')),
    CONSTRAINT fk_return_proof_attestation_order FOREIGN KEY (order_id) REFERENCES trade_order (id)
);
