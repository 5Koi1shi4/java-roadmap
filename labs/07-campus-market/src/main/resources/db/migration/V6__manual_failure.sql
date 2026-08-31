CREATE TABLE manual_failure (
    id CHAR(36) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id CHAR(36) NOT NULL,
    consumer_name VARCHAR(100) NOT NULL DEFAULT '',
    failure_class VARCHAR(20) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    published_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_manual_failure_source (source_type, source_id, consumer_name),
    CONSTRAINT ck_manual_failure_class CHECK (failure_class IN ('PERMANENT', 'EXHAUSTED')),
    CONSTRAINT ck_manual_failure_status CHECK (status IN ('NEW', 'PUBLISHED'))
);
