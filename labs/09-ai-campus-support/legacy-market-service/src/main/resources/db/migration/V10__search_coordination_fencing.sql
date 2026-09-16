ALTER TABLE search_rebuild_gate
    ADD COLUMN claim_token VARCHAR(100),
    ADD COLUMN lease_until TIMESTAMP(6);

ALTER TABLE search_index_cleanup_task
    ADD COLUMN owner_id VARCHAR(100),
    ADD COLUMN claim_token VARCHAR(100),
    ADD COLUMN lease_until TIMESTAMP(6),
    ADD COLUMN last_error VARCHAR(500),
    ADD COLUMN failure_class VARCHAR(20);

ALTER TABLE search_index_cleanup_task
    ADD CONSTRAINT ck_search_cleanup_failure_class
    CHECK (failure_class IS NULL OR failure_class IN ('TRANSIENT','PERMANENT'));

ALTER TABLE search_index_cleanup_task
    DROP CHECK ck_search_cleanup_status,
    ADD CONSTRAINT ck_search_cleanup_status CHECK (status IN ('BUILDING','NEW','RUNNING','DONE','FAILED'));
