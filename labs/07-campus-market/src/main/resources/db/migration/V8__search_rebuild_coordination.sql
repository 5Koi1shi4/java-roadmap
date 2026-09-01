ALTER TABLE search_outbox
    ADD COLUMN sequence_no BIGINT NOT NULL AUTO_INCREMENT UNIQUE AFTER id;

ALTER TABLE search_outbox
    MODIFY COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

CREATE TABLE search_rebuild_gate (
    id TINYINT NOT NULL,
    mode VARCHAR(20) NOT NULL,
    generation BIGINT NOT NULL,
    owner_id VARCHAR(100),
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_search_rebuild_gate_id CHECK (id = 1),
    CONSTRAINT ck_search_rebuild_gate_mode CHECK (mode IN ('OPEN','REBUILDING')),
    CONSTRAINT ck_search_rebuild_gate_generation CHECK (generation > 0)
);

INSERT INTO search_rebuild_gate(id, mode, generation, updated_at)
VALUES (1, 'OPEN', 1, CURRENT_TIMESTAMP(6));
