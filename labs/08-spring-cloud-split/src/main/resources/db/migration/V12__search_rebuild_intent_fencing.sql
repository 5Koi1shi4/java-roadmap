ALTER TABLE search_rebuild_intent
    ADD COLUMN owner_id VARCHAR(100),
    ADD COLUMN claim_token VARCHAR(100),
    ADD COLUMN lease_until TIMESTAMP(6),
    ADD COLUMN generation BIGINT NOT NULL DEFAULT 0,
    DROP CHECK ck_search_rebuild_intent_phase,
    ADD CONSTRAINT ck_search_rebuild_intent_phase CHECK (phase IN ('CREATED','BUILDING','SWITCHING','SWITCHED','RECONCILED')),
    ADD CONSTRAINT ck_search_rebuild_intent_claim_fields CHECK (
        (phase = 'SWITCHING' AND owner_id IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR phase <> 'SWITCHING'
    ),
    ADD KEY idx_search_rebuild_intent_claim (phase, lease_until, updated_at);
