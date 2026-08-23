CREATE TABLE orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE order_stock (
  product_id BIGINT PRIMARY KEY,
  available INT NOT NULL
);

INSERT INTO order_stock (product_id, available) VALUES (1, 10);

CREATE TABLE outbox_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id CHAR(36) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  lease_until TIMESTAMP(6) NULL,
  publish_attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1024) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  published_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_outbox_event_id (event_id)
);

CREATE TABLE consumed_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id CHAR(36) NOT NULL,
  status VARCHAR(16) NOT NULL,
  lease_until TIMESTAMP(6) NULL,
  failure_category VARCHAR(64) NULL,
  last_error VARCHAR(1024) NULL,
  completed_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_consumed_message_event_id (event_id)
);
