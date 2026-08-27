ALTER TABLE file_audit_event
    MODIFY COLUMN correlation_id VARCHAR(36) NOT NULL;
