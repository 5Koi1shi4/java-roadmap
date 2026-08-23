ALTER TABLE outbox_event
    ADD COLUMN claim_token CHAR(36) NULL AFTER lease_until;
