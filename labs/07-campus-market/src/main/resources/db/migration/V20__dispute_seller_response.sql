ALTER TABLE dispute_case
    ADD COLUMN seller_response VARCHAR(2000) NULL AFTER seller_deadline;
