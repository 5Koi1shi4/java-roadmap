CREATE TABLE idempotency_record (
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status ENUM('PROCESSING', 'SUCCEEDED') NOT NULL DEFAULT 'PROCESSING',
  response_status INT NULL,
  response_body JSON NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_idempotency_record_key UNIQUE (idempotency_key)
);
