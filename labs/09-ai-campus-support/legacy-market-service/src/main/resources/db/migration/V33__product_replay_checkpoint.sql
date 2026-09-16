ALTER TABLE product_replay_claim
    ADD COLUMN replay_id CHAR(36) NULL AFTER high_watermark;
