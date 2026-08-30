CREATE TABLE campus_user (
    id CHAR(36) NOT NULL,
    username VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campus_user_username (username),
    CONSTRAINT ck_campus_user_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE TABLE user_login_identity (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    provider_subject VARCHAR(191) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_login_identity_provider_subject (provider, provider_subject)
);

CREATE TABLE campus_role (
    id CHAR(36) NOT NULL,
    role_name VARCHAR(50) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campus_role_name (role_name)
);

CREATE TABLE user_role (
    user_id CHAR(36) NOT NULL,
    role_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE listing (
    id CHAR(36) NOT NULL,
    seller_id CHAR(36) NOT NULL,
    category_id CHAR(36),
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    unit_price_fen BIGINT NOT NULL,
    available_quantity INT NOT NULL,
    quarantined_quantity INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_listing_unit_price CHECK (unit_price_fen >= 0),
    CONSTRAINT ck_listing_available_quantity CHECK (available_quantity >= 0),
    CONSTRAINT ck_listing_quarantined_quantity CHECK (quarantined_quantity >= 0),
    CONSTRAINT ck_listing_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'SOLD_OUT', 'OFFLINE'))
);

CREATE TABLE listing_image (
    id CHAR(36) NOT NULL,
    listing_id CHAR(36) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_image_object (object_key)
);

CREATE TABLE listing_category (
    id CHAR(36) NOT NULL,
    parent_id CHAR(36),
    category_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_category_name (category_name)
);

CREATE TABLE listing_favorite (
    listing_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (listing_id, user_id)
);
