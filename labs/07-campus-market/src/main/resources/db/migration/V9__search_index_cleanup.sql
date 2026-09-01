CREATE TABLE search_index_cleanup_task (
    id CHAR(36) NOT NULL,
    index_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_search_cleanup_index (index_name),
    CONSTRAINT ck_search_cleanup_status CHECK (status IN ('NEW','DONE')),
    CONSTRAINT ck_search_cleanup_attempts CHECK (attempt_count >= 0)
);
