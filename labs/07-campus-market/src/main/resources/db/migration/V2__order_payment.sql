CREATE TABLE trade_order (
    id CHAR(36) NOT NULL,
    buyer_id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    total_amount_fen BIGINT NOT NULL,
    paid_amount_fen BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_trade_order_total CHECK (total_amount_fen >= 0),
    CONSTRAINT ck_trade_order_paid CHECK (paid_amount_fen >= 0),
    CONSTRAINT ck_trade_order_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'FULFILLING', 'COMPLETED', 'CANCELLED', 'DISPUTED'))
);

CREATE TABLE trade_order_item (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    listing_id CHAR(36) NOT NULL,
    unit_price_fen BIGINT NOT NULL,
    quantity INT NOT NULL,
    subtotal_amount_fen BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_order_item_unit_price CHECK (unit_price_fen >= 0),
    CONSTRAINT ck_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_item_subtotal CHECK (subtotal_amount_fen >= 0)
);

CREATE TABLE payment_order (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    provider_payment_id VARCHAR(191),
    amount_fen BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order_provider_payment (provider, provider_payment_id),
    CONSTRAINT ck_payment_order_amount CHECK (amount_fen >= 0),
    CONSTRAINT ck_payment_order_status CHECK (status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE TABLE refund_order (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    payment_order_id CHAR(36) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(191) NOT NULL,
    provider_refund_id VARCHAR(191),
    amount_fen BIGINT NOT NULL,
    successful_refund_fen BIGINT NOT NULL DEFAULT 0,
    reserved_refund_fen BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    next_reconcile_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_idempotency (provider, idempotency_key),
    UNIQUE KEY uk_refund_provider_refund (provider, provider_refund_id),
    KEY idx_refund_reconcile (status, next_reconcile_at),
    CONSTRAINT ck_refund_order_amount CHECK (amount_fen >= 0),
    CONSTRAINT ck_refund_successful_amount CHECK (successful_refund_fen >= 0),
    CONSTRAINT ck_refund_reserved_amount CHECK (reserved_refund_fen >= 0),
    CONSTRAINT ck_refund_order_status CHECK (status IN ('REQUESTED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE TABLE payment_attempt (
    id CHAR(36) NOT NULL,
    payment_order_id CHAR(36) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    provider_event_id VARCHAR(191),
    amount_fen BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_payment_attempt_amount CHECK (amount_fen >= 0),
    CONSTRAINT ck_payment_attempt_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED'))
);

CREATE TABLE integration_outbox (
    id CHAR(36) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    provider_event_id VARCHAR(191) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_provider_event (provider, provider_event_id),
    KEY idx_outbox_claim (status, available_at, lease_until),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE TABLE consumed_event (
    id CHAR(36) NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    consumed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consumed_event (consumer_name, event_id),
    KEY idx_consumed_event_claim (consumer_name, lease_until),
    CONSTRAINT ck_consumed_event_attempt_count CHECK (attempt_count >= 0)
);
