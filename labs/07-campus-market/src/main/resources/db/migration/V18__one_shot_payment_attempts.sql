ALTER TABLE payment_order ADD COLUMN create_attempted_at DATETIME(6) NULL AFTER response_utf8;
ALTER TABLE refund_order ADD COLUMN create_attempted_at DATETIME(6) NULL AFTER response_utf8;

UPDATE payment_order SET create_attempted_at = created_at WHERE create_attempted_at IS NULL;
UPDATE refund_order SET create_attempted_at = created_at WHERE create_attempted_at IS NULL;
