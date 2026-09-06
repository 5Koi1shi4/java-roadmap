ALTER TABLE return_case
    ADD COLUMN resolution_type VARCHAR(24) NULL AFTER refund_status,
    ADD CONSTRAINT ck_return_case_resolution_type CHECK (resolution_type IS NULL OR resolution_type IN ('REFUND_ONLY','RETURN_AND_REFUND'));
