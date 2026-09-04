CREATE INDEX idx_payment_reconcile_due
    ON payment_order (status, next_reconcile_at, reconcile_lease_until);

CREATE INDEX idx_refund_reconcile_due
    ON refund_order (status, next_reconcile_at, reconcile_lease_until);
