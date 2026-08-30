CREATE TABLE object_upload_session (
    id CHAR(36) NOT NULL,
    owner_id VARCHAR(100) NOT NULL,
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    object_key VARCHAR(512) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_upload_object_key (object_key),
    KEY idx_object_upload_lease (status, lease_until),
    CONSTRAINT ck_object_upload_status CHECK (status IN ('OPEN', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    CONSTRAINT ck_object_upload_attempt_count CHECK (attempt_count >= 0)
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
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_storage_cleanup_business_key (cleanup_business_key),
    KEY idx_storage_cleanup_claim (status, run_after, lease_until),
    CONSTRAINT ck_storage_cleanup_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_storage_cleanup_attempt_count CHECK (attempt_count >= 0)
);

CREATE TABLE conversation (
    id CHAR(36) NOT NULL,
    subject VARCHAR(200),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_conversation_status CHECK (status IN ('OPEN', 'CLOSED', 'ARCHIVED'))
);

CREATE TABLE conversation_member (
    conversation_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    joined_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE message (
    id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    sender_id CHAR(36) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_message_conversation_created (conversation_id, created_at),
    CONSTRAINT ck_message_status CHECK (status IN ('VISIBLE', 'RECALLED', 'DELETED'))
);

CREATE TABLE notification (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    payload JSON NOT NULL,
    read_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_user_read (user_id, read_at, created_at)
);

CREATE TABLE audit_log (
    id CHAR(36) NOT NULL,
    actor_id CHAR(36),
    action VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id CHAR(36),
    details JSON,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_log_aggregate (aggregate_type, aggregate_id, created_at)
);
