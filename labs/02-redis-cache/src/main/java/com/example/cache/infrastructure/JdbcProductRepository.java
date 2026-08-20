package com.example.cache.infrastructure;

import com.example.cache.domain.Product;
import com.example.cache.domain.ProductRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

public final class JdbcProductRepository implements ProductRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Product> findById(long id) {
        return jdbcTemplate.query(
                        "SELECT id, name, price_in_cents FROM products WHERE id = ?",
                        (resultSet, rowNumber) -> new Product(
                                resultSet.getLong("id"),
                                resultSet.getString("name"),
                                resultSet.getLong("price_in_cents")),
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public void update(Product product) {
        int updatedRows = jdbcTemplate.update(
                "UPDATE products SET name = ?, price_in_cents = ? WHERE id = ?",
                product.name(), product.priceInCents(), product.id());
        if (updatedRows != 1) {
            throw new IllegalArgumentException("product does not exist: " + product.id());
        }
    }
}
