CREATE TABLE search_coordination (
  id TINYINT PRIMARY KEY,
  dispatcher_paused BOOLEAN NOT NULL DEFAULT FALSE,
  active_rebuild_id CHAR(36) NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_search_coordination_singleton CHECK (id = 1)
);
INSERT INTO search_coordination(id, dispatcher_paused) VALUES (1, FALSE);

CREATE TABLE product (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  subtitle VARCHAR(255) NULL,
  description TEXT NOT NULL,
  category_code VARCHAR(64) NOT NULL,
  category_name VARCHAR(120) NOT NULL,
  price DECIMAL(12,2) NOT NULL,
  status VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT chk_product_price CHECK (price >= 0),
  CONSTRAINT chk_product_status CHECK (status IN ('ON_SALE','OFF_SHELF','DELETED')),
  CONSTRAINT chk_product_version CHECK (version > 0)
);

CREATE TABLE search_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id CHAR(36) NOT NULL,
  product_id BIGINT NOT NULL,
  product_version BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  owner VARCHAR(128) NULL,
  claim_token CHAR(36) NULL,
  lease_until TIMESTAMP(6) NULL,
  available_at TIMESTAMP(6) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1024) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  completed_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_search_outbox_event_id (event_id),
  UNIQUE KEY uk_search_outbox_product_version_type
    (product_id, product_version, event_type),
  KEY idx_search_outbox_claim (status, available_at, lease_until, id),
  CONSTRAINT chk_search_outbox_status
    CHECK (status IN ('NEW','PROCESSING','COMPLETED','FAILED')),
  CONSTRAINT chk_search_outbox_type
    CHECK (event_type IN ('PRODUCT_UPSERT','PRODUCT_DELETE'))
);

CREATE TABLE search_rebuild_job (
  job_id CHAR(36) PRIMARY KEY,
  target_index VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL,
  phase VARCHAR(32) NOT NULL,
  owner VARCHAR(128) NOT NULL,
  lease_until TIMESTAMP(6) NOT NULL,
  start_watermark BIGINT NULL,
  final_watermark BIGINT NULL,
  imported_count BIGINT NOT NULL DEFAULT 0,
  difference_count BIGINT NOT NULL DEFAULT 0,
  last_error VARCHAR(1024) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  completed_at TIMESTAMP(6) NULL,
  CONSTRAINT chk_search_rebuild_status
    CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED'))
);
