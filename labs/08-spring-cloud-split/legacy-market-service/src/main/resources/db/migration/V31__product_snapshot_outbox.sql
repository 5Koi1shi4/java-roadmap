ALTER TABLE search_outbox
    ADD COLUMN schema_version INT NOT NULL DEFAULT 2 AFTER payload,
    ADD CONSTRAINT ck_search_outbox_schema_version CHECK (schema_version = 2);
