ALTER TABLE payment_order
    ADD COLUMN reconcile_owner VARCHAR(100),
    ADD COLUMN reconcile_token VARCHAR(100),
    ADD COLUMN reconcile_lease_until TIMESTAMP(6);

ALTER TABLE refund_order
    ADD COLUMN reconcile_owner VARCHAR(100),
    ADD COLUMN reconcile_token VARCHAR(100),
    ADD COLUMN reconcile_lease_until TIMESTAMP(6);
