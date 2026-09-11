ALTER TABLE integration_outbox
    ADD COLUMN occurred_at TIMESTAMP(6) NULL AFTER schema_version,
    ADD COLUMN failure_class VARCHAR(20) NULL AFTER published_at;

UPDATE integration_outbox SET occurred_at=created_at WHERE occurred_at IS NULL;

ALTER TABLE integration_outbox
    MODIFY COLUMN occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

ALTER TABLE integration_outbox
    ADD CONSTRAINT ck_outbox_failure_class
    CHECK (failure_class IS NULL OR failure_class IN ('PERMANENT', 'EXHAUSTED'));
