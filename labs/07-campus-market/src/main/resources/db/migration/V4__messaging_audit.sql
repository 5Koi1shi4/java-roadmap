CREATE TABLE trade_review (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    reviewer_id CHAR(36) NOT NULL,
    reviewee_id CHAR(36) NOT NULL,
    rating TINYINT NOT NULL,
    review_text VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trade_review_order_reviewer (order_id, reviewer_id),
    CONSTRAINT ck_trade_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT fk_trade_review_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_trade_review_reviewer FOREIGN KEY (reviewer_id) REFERENCES campus_user (id),
    CONSTRAINT fk_trade_review_reviewee FOREIGN KEY (reviewee_id) REFERENCES campus_user (id)
);

CREATE TABLE audit_event (
    id CHAR(36) NOT NULL,
    actor_id CHAR(36),
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id CHAR(36),
    result VARCHAR(20) NOT NULL,
    failure_class VARCHAR(40),
    details JSON,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_event_resource (resource_type, resource_id, occurred_at),
    CONSTRAINT ck_audit_event_result CHECK (result IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT ck_audit_event_failure CHECK (failure_class IS NULL OR failure_class IN ('VALIDATION', 'AUTHENTICATION', 'AUTHORIZATION', 'CONFLICT', 'DEPENDENCY', 'INTERNAL')),
    CONSTRAINT fk_audit_event_actor FOREIGN KEY (actor_id) REFERENCES campus_user (id)
);

CREATE TABLE integration_outbox (
    id CHAR(36) NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    schema_version INT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_integration_outbox_event_id (event_id),
    KEY idx_outbox_claim (status, available_at, lease_until),
    CONSTRAINT ck_outbox_aggregate_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_outbox_schema_version CHECK (schema_version = 1),
    CONSTRAINT ck_outbox_status CHECK (status IN ('NEW', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE TABLE consumed_event (
    id CHAR(36) NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    completed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consumed_event (consumer_name, event_id),
    KEY idx_consumed_event_claim (status, lease_until),
    CONSTRAINT ck_consumed_event_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_consumed_event_attempt_count CHECK (attempt_count >= 0)
);

CREATE TABLE object_upload_session (
    id CHAR(36) NOT NULL,
    submitted_by CHAR(36) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    claim_token VARCHAR(100),
    owner_id VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_upload_object_key (object_key),
    KEY idx_object_upload_lease (status, lease_until),
    CONSTRAINT ck_object_upload_purpose CHECK (purpose IN ('LISTING_MEDIA', 'DISPUTE_EVIDENCE', 'WARRANTY_EVIDENCE')),
    CONSTRAINT ck_object_upload_status CHECK (status IN ('OPEN', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    CONSTRAINT ck_object_upload_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT fk_object_upload_submitter FOREIGN KEY (submitted_by) REFERENCES campus_user (id)
);

CREATE TABLE storage_cleanup_task (
    id CHAR(36) NOT NULL,
    cleanup_business_key VARCHAR(191) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    run_after TIMESTAMP(6) NOT NULL,
    failure_class VARCHAR(40),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_storage_cleanup_business_key (cleanup_business_key),
    KEY idx_storage_cleanup_claim (status, run_after, lease_until),
    CONSTRAINT ck_storage_cleanup_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_storage_cleanup_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_storage_cleanup_failure CHECK (failure_class IS NULL OR failure_class IN ('TRANSIENT', 'PERMANENT', 'UNKNOWN'))
);
