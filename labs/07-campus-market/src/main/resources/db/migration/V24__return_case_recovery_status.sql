ALTER TABLE return_case
    DROP CHECK ck_return_case_status,
    ADD CONSTRAINT ck_return_case_status CHECK (status IN ('REQUESTED','AWAITING_PROOF','CONFIRMED','REJECTED','EXPIRED','ESCALATED'));
