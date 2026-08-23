CREATE TABLE manual_failure (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id CHAR(36) NULL,
  payload LONGTEXT NOT NULL,
  failure_category VARCHAR(64) NOT NULL,
  last_error VARCHAR(2048) NOT NULL,
  retry_count INT NOT NULL,
  manual_delivery_status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  INDEX ix_manual_failure_status (manual_delivery_status, attempts)
);
