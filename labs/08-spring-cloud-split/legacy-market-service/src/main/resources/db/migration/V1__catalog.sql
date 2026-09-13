CREATE TABLE listing (
    id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    category VARCHAR(100) NOT NULL,
    unit_price_fen BIGINT NOT NULL,
    available_quantity INT NOT NULL,
    quarantined_quantity INT NOT NULL DEFAULT 0,
    warranty_days INT,
    warranty_scope TEXT,
    manufacturer_warranty_proof_snapshot TEXT,
    manufacturer_warranty_expires_at TIMESTAMP(6),
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_listing_seller_status (seller_id, status),
    CONSTRAINT ck_listing_unit_price CHECK (unit_price_fen >= 0),
    CONSTRAINT ck_listing_available_quantity CHECK (available_quantity >= 0),
    CONSTRAINT ck_listing_quarantined_quantity CHECK (quarantined_quantity >= 0),
    CONSTRAINT ck_listing_warranty_days CHECK (warranty_days IN (30, 90, 180, 365) OR warranty_days IS NULL),
    CONSTRAINT ck_listing_status CHECK (status IN ('DRAFT', 'ON_SALE', 'SOLD_OUT', 'OFF_SALE')),
    CONSTRAINT ck_listing_version CHECK (version >= 0)
);

CREATE TABLE listing_media (
    id CHAR(36) NOT NULL,
    listing_id CHAR(36) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_media_object_key (object_key),
    KEY idx_listing_media_listing_order (listing_id, sort_order),
    CONSTRAINT ck_listing_media_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_listing_media_sort CHECK (sort_order >= 0),
    CONSTRAINT fk_listing_media_listing FOREIGN KEY (listing_id) REFERENCES listing (id)
);

CREATE TABLE inventory_movement (
    id CHAR(36) NOT NULL,
    business_key VARCHAR(191) NOT NULL,
    listing_id CHAR(36) NOT NULL,
    order_id CHAR(36),
    dispute_case_id CHAR(36),
    reason VARCHAR(40) NOT NULL,
    quantity_delta INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_movement_business_key (business_key),
    KEY idx_inventory_movement_listing_time (listing_id, created_at),
    CONSTRAINT ck_inventory_movement_delta CHECK (quantity_delta <> 0),
    CONSTRAINT fk_inventory_movement_listing FOREIGN KEY (listing_id) REFERENCES listing (id)
);

CREATE TABLE search_outbox (
    id CHAR(36) NOT NULL,
    listing_id CHAR(36) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_search_outbox_claim (status, available_at, lease_until),
    CONSTRAINT ck_search_outbox_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_search_outbox_status CHECK (status IN ('NEW', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_search_outbox_attempts CHECK (attempt_count >= 0),
    CONSTRAINT fk_search_outbox_listing FOREIGN KEY (listing_id) REFERENCES listing (id)
);
