CREATE TABLE product_projection (
    listing_id CHAR(36) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    category VARCHAR(100) NOT NULL,
    unit_price_fen BIGINT NOT NULL,
    available_quantity INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (listing_id),
    KEY idx_product_projection_search (status, category, unit_price_fen, listing_id),
    CONSTRAINT ck_product_projection_aggregate_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_product_projection_unit_price CHECK (unit_price_fen >= 0),
    CONSTRAINT ck_product_projection_available_quantity CHECK (available_quantity >= 0),
    CONSTRAINT ck_product_projection_status CHECK (status IN ('DRAFT', 'ON_SALE', 'SOLD_OUT', 'OFF_SALE'))
);

CREATE TABLE product_inbox (
    event_id CHAR(36) NOT NULL,
    completed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT ck_product_inbox_event_id CHECK (CHAR_LENGTH(event_id) = 36)
);

CREATE TABLE product_index_outbox (
    id CHAR(36) NOT NULL,
    listing_id CHAR(36) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_index_outbox_listing_version (listing_id, aggregate_version),
    KEY idx_product_index_outbox_claim (status, available_at, lease_until),
    CONSTRAINT ck_product_index_outbox_aggregate_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_product_index_outbox_status CHECK (status IN ('NEW', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_product_index_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_product_index_outbox_claim_fields CHECK (
        (status = 'PUBLISHING' AND owner_id IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PUBLISHING' AND owner_id IS NULL AND claim_token IS NULL AND lease_until IS NULL)
    )
);

CREATE TABLE product_rebuild_gate (
    id TINYINT NOT NULL,
    mode VARCHAR(20) NOT NULL,
    generation BIGINT NOT NULL,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_product_rebuild_gate_id CHECK (id = 1),
    CONSTRAINT ck_product_rebuild_gate_mode CHECK (mode IN ('OPEN', 'REBUILDING')),
    CONSTRAINT ck_product_rebuild_gate_generation CHECK (generation > 0),
    CONSTRAINT ck_product_rebuild_gate_claim_fields CHECK (
        (mode = 'REBUILDING' AND owner_id IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (mode = 'OPEN' AND owner_id IS NULL AND claim_token IS NULL AND lease_until IS NULL)
    )
);

INSERT INTO product_rebuild_gate(id, mode, generation, updated_at)
VALUES (1, 'OPEN', 1, CURRENT_TIMESTAMP(6));

CREATE TABLE product_index_cleanup_task (
    id CHAR(36) NOT NULL,
    index_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error VARCHAR(500),
    failure_class VARCHAR(20),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_index_cleanup_index_name (index_name),
    KEY idx_product_index_cleanup_claim (status, available_at, lease_until, created_at),
    CONSTRAINT ck_product_index_cleanup_status
        CHECK (status IN ('NEW', 'BUILDING', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT ck_product_index_cleanup_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_product_index_cleanup_failure_class
        CHECK (failure_class IS NULL OR failure_class IN ('TRANSIENT', 'PERMANENT')),
    CONSTRAINT ck_product_index_cleanup_claim_fields CHECK (
        (status IN ('BUILDING', 'RUNNING')
            AND owner_id IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR status IN ('NEW', 'DONE', 'FAILED')
    )
);
