ALTER TABLE consumed_message
    ADD COLUMN claim_token CHAR(36) NULL AFTER lease_until;
