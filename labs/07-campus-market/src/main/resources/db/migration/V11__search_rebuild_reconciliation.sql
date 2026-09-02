CREATE TABLE search_rebuild_intent (
    id CHAR(36) NOT NULL,
    target_index VARCHAR(200) NOT NULL,
    previous_index VARCHAR(200),
    phase VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_search_rebuild_intent_target (target_index),
    KEY idx_search_rebuild_intent_phase (phase, updated_at),
    CONSTRAINT ck_search_rebuild_intent_phase CHECK (phase IN ('CREATED','BUILDING','SWITCHED','RECONCILED'))
);

ALTER TABLE search_index_cleanup_task
    ADD KEY idx_search_cleanup_claim (status, available_at, lease_until, created_at),
    ADD CONSTRAINT ck_search_cleanup_claim_fields CHECK (
        (status IN ('BUILDING','RUNNING') AND owner_id IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR status IN ('NEW','DONE','FAILED')
    );
