CREATE TABLE IF NOT EXISTS products (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price_in_cents BIGINT NOT NULL
);

INSERT INTO products (id, name, price_in_cents)
VALUES (7, 'Java 编程思想', 9900)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    price_in_cents = VALUES(price_in_cents);
