ALTER TABLE manual_failure
  ADD COLUMN claim_token CHAR(36) NULL AFTER manual_delivery_status,
  ADD COLUMN lease_until TIMESTAMP(6) NULL AFTER claim_token,
  ADD INDEX ix_manual_failure_claim (manual_delivery_status, lease_until, attempts);
