CREATE TABLE campus_user (
    id CHAR(36) NOT NULL,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campus_user_email (email),
    CONSTRAINT ck_campus_user_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE TABLE email_verification (
    id CHAR(36) NOT NULL,
    user_id CHAR(36),
    email VARCHAR(320) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    code_hmac VARBINARY(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_email_verification_email (email, purpose, status),
    CONSTRAINT ck_email_verification_purpose CHECK (purpose IN ('REGISTER', 'LOGIN', 'CHANGE_EMAIL')),
    CONSTRAINT ck_email_verification_status CHECK (status IN ('PENDING', 'VERIFIED', 'EXPIRED', 'LOCKED')),
    CONSTRAINT ck_email_verification_attempts CHECK (attempt_count >= 0),
    CONSTRAINT fk_email_verification_user FOREIGN KEY (user_id) REFERENCES campus_user (id)
);

CREATE TABLE external_identity (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    subject VARCHAR(191) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_external_identity_provider_subject (provider, subject),
    CONSTRAINT ck_external_identity_status CHECK (status IN ('ACTIVE', 'UNBOUND')),
    CONSTRAINT fk_external_identity_user FOREIGN KEY (user_id) REFERENCES campus_user (id)
);
