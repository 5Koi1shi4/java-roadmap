ALTER TABLE dispute_case
    ADD COLUMN assigned_admin_id CHAR(36) NULL AFTER initiator_id,
    ADD KEY idx_dispute_case_admin (assigned_admin_id, status, admin_deadline),
    ADD CONSTRAINT fk_dispute_case_admin FOREIGN KEY (assigned_admin_id) REFERENCES campus_user (id);

ALTER TABLE dispute_evidence
    ADD CONSTRAINT ck_dispute_evidence_media_type CHECK (media_type IN ('image/jpeg', 'image/png', 'image/webp', 'application/pdf', 'video/mp4'));
