CREATE TABLE product_projection_readiness (
    id TINYINT NOT NULL,
    state VARCHAR(20) NOT NULL,
    replay_id CHAR(36) NULL,
    source_high_watermark BIGINT NOT NULL DEFAULT 0,
    index_high_watermark BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_product_projection_readiness_id CHECK (id = 1),
    CONSTRAINT ck_product_projection_readiness_state
        CHECK (state IN ('WAITING', 'CATCHING_UP', 'READY', 'BLOCKED')),
    CONSTRAINT ck_product_projection_readiness_source_high_watermark
        CHECK (source_high_watermark >= 0),
    CONSTRAINT ck_product_projection_readiness_index_high_watermark
        CHECK (index_high_watermark >= 0),
    CONSTRAINT ck_product_projection_readiness_replay_id
        CHECK (replay_id IS NULL OR CHAR_LENGTH(replay_id) = 36)
);

INSERT INTO product_projection_readiness(
    id,state,replay_id,source_high_watermark,index_high_watermark,updated_at)
VALUES (1,'WAITING',NULL,0,0,CURRENT_TIMESTAMP(6));
