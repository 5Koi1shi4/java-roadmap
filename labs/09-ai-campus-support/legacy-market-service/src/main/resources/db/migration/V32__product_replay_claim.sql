ALTER TABLE search_outbox
    ADD COLUMN failure_class VARCHAR(20) NULL AFTER status,
    ADD CONSTRAINT ck_search_outbox_failure_class
        CHECK (failure_class IS NULL OR failure_class IN ('PERMANENT', 'EXHAUSTED'));

CREATE TABLE product_replay_claim (
    id TINYINT NOT NULL,
    high_watermark BIGINT NOT NULL DEFAULT 0,
    next_sequence_no BIGINT NOT NULL DEFAULT 1,
    owner_id VARCHAR(100),
    claim_token VARCHAR(100),
    lease_until TIMESTAMP(6),
    status VARCHAR(20) NOT NULL DEFAULT 'IDLE',
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_product_replay_claim_singleton CHECK (id = 1),
    CONSTRAINT ck_product_replay_claim_high_watermark CHECK (high_watermark >= 0),
    CONSTRAINT ck_product_replay_claim_next_sequence CHECK (next_sequence_no > 0),
    CONSTRAINT ck_product_replay_claim_status CHECK (status IN ('IDLE', 'RUNNING'))
);

INSERT INTO product_replay_claim(id, high_watermark, next_sequence_no, status)
VALUES (1, 0, 1, 'IDLE');
