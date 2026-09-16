ALTER TABLE product_index_outbox
    ADD COLUMN sequence_no BIGINT NOT NULL AUTO_INCREMENT AFTER id,
    ADD UNIQUE KEY uk_product_index_outbox_sequence_no (sequence_no);

ALTER TABLE product_rebuild_gate
    ADD COLUMN intent VARCHAR(20) NOT NULL DEFAULT 'NONE' AFTER mode,
    ADD COLUMN rebuild_index VARCHAR(200) NULL AFTER claim_token,
    ADD COLUMN snapshot_sequence_no BIGINT NOT NULL DEFAULT 0 AFTER generation,
    ADD COLUMN cutover_sequence_no BIGINT NOT NULL DEFAULT 0 AFTER snapshot_sequence_no,
    ADD CONSTRAINT ck_product_rebuild_gate_intent
        CHECK (intent IN ('NONE', 'BUILDING', 'CUTOVER')),
    ADD CONSTRAINT ck_product_rebuild_gate_snapshot_sequence
        CHECK (snapshot_sequence_no >= 0),
    ADD CONSTRAINT ck_product_rebuild_gate_cutover_sequence
        CHECK (cutover_sequence_no >= snapshot_sequence_no);

ALTER TABLE product_index_cleanup_task
    ADD COLUMN generation BIGINT NOT NULL DEFAULT 1 AFTER index_name,
    ADD CONSTRAINT ck_product_index_cleanup_generation CHECK (generation >= 0);
