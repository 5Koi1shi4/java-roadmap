ALTER TABLE manual_failure
    ADD COLUMN owner_id VARCHAR(100),
    ADD COLUMN claim_token VARCHAR(100),
    ADD COLUMN lease_until TIMESTAMP(6),
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD CONSTRAINT ck_manual_failure_attempt_count CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_manual_failure_claim_fields CHECK (
        (status = 'PUBLISHING' AND owner_id IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PUBLISHING' AND owner_id IS NULL AND claim_token IS NULL AND lease_until IS NULL)
    );

ALTER TABLE manual_failure
    DROP CHECK ck_manual_failure_status,
    ADD CONSTRAINT ck_manual_failure_status CHECK (status IN ('NEW', 'PUBLISHING', 'PUBLISHED', 'FAILED'));

CREATE INDEX idx_manual_failure_claim ON manual_failure (status, available_at, lease_until);
