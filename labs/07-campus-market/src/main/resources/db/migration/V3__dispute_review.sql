CREATE TABLE dispute_case (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    initiator_id CHAR(36) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL,
    opened_at TIMESTAMP(6) NOT NULL,
    resolved_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_dispute_case_status CHECK (status IN ('OPEN', 'UNDER_REVIEW', 'RESOLVED', 'REJECTED', 'CANCELLED'))
);

CREATE TABLE dispute_evidence (
    id CHAR(36) NOT NULL,
    dispute_case_id CHAR(36) NOT NULL,
    submitted_by CHAR(36) NOT NULL,
    object_key VARCHAR(512),
    content TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE review (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    reviewer_id CHAR(36) NOT NULL,
    reviewee_id CHAR(36) NOT NULL,
    rating TINYINT NOT NULL,
    content TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_order_reviewer (order_id, reviewer_id),
    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5)
);

CREATE TABLE warranty_case (
    id CHAR(36) NOT NULL,
    warranty_case_id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    buyer_id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    warranty_days INT,
    status VARCHAR(24) NOT NULL,
    opened_at TIMESTAMP(6) NOT NULL,
    closed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_warranty_case_id (warranty_case_id),
    CONSTRAINT ck_warranty_days CHECK (warranty_days IN (30, 90, 180, 365) OR warranty_days IS NULL),
    CONSTRAINT ck_warranty_case_status CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED', 'CLOSED'))
);

CREATE TABLE seller_obligation (
    id CHAR(36) NOT NULL,
    warranty_case_id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    obligation_amount_fen BIGINT NOT NULL,
    funded_amount_fen BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    due_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seller_obligation_warranty_case (warranty_case_id),
    CONSTRAINT ck_obligation_amount CHECK (obligation_amount_fen >= 0),
    CONSTRAINT ck_obligation_funded CHECK (funded_amount_fen >= 0 AND funded_amount_fen <= obligation_amount_fen),
    CONSTRAINT ck_seller_obligation_status CHECK (status IN ('OPEN', 'PARTIALLY_FUNDED', 'FUNDED', 'CANCELLED'))
);
