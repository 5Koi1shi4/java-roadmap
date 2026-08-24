package com.example.search.infrastructure.persistence;

import com.example.search.application.product.ProductRepository;
import com.example.search.domain.Product;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

@Repository
public class JdbcProductRepository implements ProductRepository {
    private final JdbcTemplate jdbc;

    public JdbcProductRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Product insert(ProductDetails details, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO product(name, subtitle, description, category_code, category_name, price, status, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, details.name());
            ps.setString(2, details.subtitle());
            ps.setString(3, details.description());
            ps.setString(4, details.categoryCode());
            ps.setString(5, details.categoryName());
            ps.setBigDecimal(6, details.price());
            ps.setString(7, details.status().name());
            ps.setTimestamp(8, Timestamp.from(now));
            ps.setTimestamp(9, Timestamp.from(now));
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("database did not return product id");
        return new Product(id.longValue(), details, 1, now, now);
    }

    @Override
    public int updateIfVersionMatches(long id, long expectedVersion, ProductDetails details, Instant now) {
        return jdbc.update("UPDATE product SET name=?, subtitle=?, description=?, category_code=?, category_name=?, price=?, status=?, version=version+1, updated_at=? WHERE id=? AND version=? AND status <> 'DELETED'",
                details.name(), details.subtitle(), details.description(), details.categoryCode(), details.categoryName(), details.price(), details.status().name(), Timestamp.from(now), id, expectedVersion);
    }

    @Override
    public int markDeletedIfVersionMatches(long id, long expectedVersion, Instant now) {
        return jdbc.update("UPDATE product SET status='DELETED', version=version+1, updated_at=? WHERE id=? AND version=? AND status <> 'DELETED'",
                Timestamp.from(now), id, expectedVersion);
    }

    @Override
    public Optional<Product> findById(long id) {
        return jdbc.query("SELECT id, name, subtitle, description, category_code, category_name, price, status, version, created_at, updated_at FROM product WHERE id=?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), id);
    }

    @Override
    public List<Product> findPageAfter(long lastId, int size) {
        if (lastId < 0) throw new IllegalArgumentException("lastId must not be negative");
        if (size < 1 || size > 500) throw new IllegalArgumentException("size must be between 1 and 500");
        return jdbc.query("SELECT id, name, subtitle, description, category_code, category_name, price, status, version, created_at, updated_at "
                        + "FROM product WHERE id > ? ORDER BY id LIMIT ?", (rs, rowNum) -> map(rs), lastId, size);
    }

    private Product map(java.sql.ResultSet rs) throws java.sql.SQLException {
        ProductDetails details = ProductDetails.fromStored(rs.getString("name"), rs.getString("subtitle"),
                rs.getString("description"), rs.getString("category_code"), rs.getString("category_name"),
                rs.getBigDecimal("price"), ProductStatus.valueOf(rs.getString("status")));
        return new Product(rs.getLong("id"), details, rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
