ALTER TABLE product_index_outbox
    ADD COLUMN last_error VARCHAR(500) NULL AFTER attempt_count,
    ADD COLUMN failure_class VARCHAR(20) NULL AFTER last_error,
    ADD CONSTRAINT ck_product_index_outbox_failure_class
        CHECK (failure_class IS NULL OR failure_class IN ('TRANSIENT', 'PERMANENT'));
