CREATE TABLE seller_withdrawal (
    id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(191) NOT NULL,
    amount_fen BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seller_withdrawal_idempotency (seller_id, idempotency_key),
    CONSTRAINT ck_seller_withdrawal_amount CHECK (amount_fen > 0),
    CONSTRAINT ck_seller_withdrawal_status CHECK (status IN ('REQUESTED','COMPLETED','FAILED')),
    CONSTRAINT fk_seller_withdrawal_seller FOREIGN KEY (seller_id) REFERENCES campus_user (id)
);
