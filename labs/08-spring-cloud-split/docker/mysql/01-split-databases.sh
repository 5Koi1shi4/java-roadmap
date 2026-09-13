#!/usr/bin/env bash
set -Eeuo pipefail

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
: "${IDENTITY_APP_PASSWORD:?IDENTITY_APP_PASSWORD is required}"
: "${IDENTITY_MIGRATOR_PASSWORD:?IDENTITY_MIGRATOR_PASSWORD is required}"
: "${MARKET_APP_PASSWORD:?MARKET_APP_PASSWORD is required}"
: "${MARKET_MIGRATOR_PASSWORD:?MARKET_MIGRATOR_PASSWORD is required}"

escape_sql_literal() {
    printf "%s" "$1" | sed "s/'/''/g"
}

identity_app_password="$(escape_sql_literal "${IDENTITY_APP_PASSWORD}")"
identity_migrator_password="$(escape_sql_literal "${IDENTITY_MIGRATOR_PASSWORD}")"
market_app_password="$(escape_sql_literal "${MARKET_APP_PASSWORD}")"
market_migrator_password="$(escape_sql_literal "${MARKET_MIGRATOR_PASSWORD}")"

MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=socket -uroot --batch --skip-column-names <<SQL
SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES';
CREATE DATABASE IF NOT EXISTS identity_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS market_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'identity_app'@'%' IDENTIFIED BY '${identity_app_password}';
CREATE USER IF NOT EXISTS 'identity_migrator'@'%' IDENTIFIED BY '${identity_migrator_password}';
CREATE USER IF NOT EXISTS 'market_app'@'%' IDENTIFIED BY '${market_app_password}';
CREATE USER IF NOT EXISTS 'market_migrator'@'%' IDENTIFIED BY '${market_migrator_password}';

GRANT SELECT, INSERT, UPDATE, DELETE ON identity_db.* TO 'identity_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP
    ON identity_db.* TO 'identity_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON market_db.* TO 'market_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP
    ON market_db.* TO 'market_migrator'@'%';
FLUSH PRIVILEGES;
SQL
