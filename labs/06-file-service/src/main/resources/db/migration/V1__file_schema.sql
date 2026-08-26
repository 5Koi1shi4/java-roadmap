CREATE TABLE upload_session (
  session_id CHAR(36) PRIMARY KEY,
  uploader_id BIGINT NOT NULL,
  temp_key VARCHAR(255) NOT NULL,
  owner_token CHAR(36) NOT NULL,
  status VARCHAR(16) NOT NULL,
  lease_until TIMESTAMP(6) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  declared_type VARCHAR(127) NULL,
  actual_size BIGINT NULL,
  detected_type VARCHAR(64) NULL,
  content_hash CHAR(64) NULL,
  blob_id BIGINT NULL,
  file_id CHAR(36) NULL,
  failure_code VARCHAR(64) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_upload_temp_key (temp_key),
  KEY idx_upload_expiry (status, expires_at, lease_until),
  CONSTRAINT chk_upload_status CHECK
    (status IN ('RECEIVING','VALIDATED','FINALIZING','COMPLETED','FAILED','EXPIRED')),
  CONSTRAINT chk_upload_user CHECK (uploader_id > 0),
  CONSTRAINT chk_upload_size CHECK (actual_size IS NULL OR actual_size >= 0)
);

CREATE TABLE stored_blob (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  content_hash CHAR(64) NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  size_bytes BIGINT NOT NULL,
  media_type VARCHAR(64) NOT NULL,
  reference_count BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  generation BIGINT NOT NULL,
  staging_session_id CHAR(36) NULL,
  staging_owner_token CHAR(36) NULL,
  staging_lease_until TIMESTAMP(6) NULL,
  cleanup_token CHAR(36) NULL,
  cleanup_lease_until TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_blob_content_hash (content_hash),
  UNIQUE KEY uk_blob_object_key (object_key),
  KEY idx_blob_staging (status, staging_lease_until),
  CONSTRAINT chk_blob_status CHECK
    (status IN ('STAGING','READY','PENDING_DELETE','DELETING','DELETED')),
  CONSTRAINT chk_blob_size CHECK (size_bytes >= 0),
  CONSTRAINT chk_blob_refs CHECK (reference_count >= 0),
  CONSTRAINT chk_blob_generation CHECK (generation > 0)
);

CREATE TABLE stored_file (
  file_id CHAR(36) PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  blob_id BIGINT NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  deleted_at TIMESTAMP(6) NULL,
  KEY idx_file_owner (owner_id, status, created_at),
  KEY idx_file_blob (blob_id, status),
  CONSTRAINT fk_file_blob FOREIGN KEY (blob_id) REFERENCES stored_blob(id),
  CONSTRAINT chk_file_owner CHECK (owner_id > 0),
  CONSTRAINT chk_file_status CHECK (status IN ('ACTIVE','DELETED'))
);

CREATE TABLE file_grant (
  file_id CHAR(36) NOT NULL,
  grantee_user_id BIGINT NOT NULL,
  granted_by BIGINT NOT NULL,
  granted_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (file_id, grantee_user_id),
  CONSTRAINT fk_grant_file FOREIGN KEY (file_id) REFERENCES stored_file(file_id),
  CONSTRAINT chk_grant_users CHECK (grantee_user_id > 0 AND granted_by > 0)
);

CREATE TABLE storage_cleanup_task (
  task_id CHAR(36) PRIMARY KEY,
  task_type VARCHAR(20) NOT NULL,
  target_id VARCHAR(64) NOT NULL,
  target_generation BIGINT NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  source_session_id CHAR(36) NULL,
  status VARCHAR(16) NOT NULL,
  owner VARCHAR(128) NULL,
  claim_token CHAR(36) NULL,
  lease_until TIMESTAMP(6) NULL,
  available_at TIMESTAMP(6) NOT NULL,
  attempt_count INT NOT NULL,
  last_error VARCHAR(512) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  completed_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_cleanup_target (task_type, target_id, target_generation),
  KEY idx_cleanup_claim (status, available_at, lease_until, task_id),
  CONSTRAINT chk_cleanup_type CHECK (task_type IN ('TEMP_OBJECT','BLOB_OBJECT')),
  CONSTRAINT chk_cleanup_status CHECK (status IN ('NEW','PROCESSING','COMPLETED','FAILED')),
  CONSTRAINT chk_cleanup_generation CHECK (target_generation > 0),
  CONSTRAINT chk_cleanup_attempt CHECK (attempt_count >= 0)
);

CREATE TABLE file_audit_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  correlation_id CHAR(36) NOT NULL,
  actor_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  file_id CHAR(36) NULL,
  target_user_id BIGINT NULL,
  result VARCHAR(24) NOT NULL,
  failure_code VARCHAR(64) NULL,
  client_trace_id VARCHAR(128) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  KEY idx_audit_file_time (file_id, created_at),
  KEY idx_audit_actor_time (actor_id, created_at),
  CONSTRAINT chk_audit_actor CHECK (actor_id > 0)
);
