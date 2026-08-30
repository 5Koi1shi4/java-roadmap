package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.catalog.application.InventoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = CampusMarketApplication.class)
@ActiveProfiles("local")
class InventoryIT extends SharedContainers {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryPort inventory;

    @Test
    void quarantineAddsIsolatedStockAndIdempotencyBindsReason() {
        UUID seller = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            seller.toString(), seller + "@stu.example.edu.cn", "hash");
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,quarantined_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'ON_SALE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材", 100, 5, 0);

        assertThat(inventory.quarantine(listing, 2, "return-1")).isTrue();
        assertThat(counts(listing)).containsExactly(5, 2);
        assertThat(inventory.quarantine(listing, 2, "return-1")).isTrue();
        assertThat(counts(listing)).containsExactly(5, 2);
        assertThatThrownBy(() -> inventory.deduct(listing, 2, "return-1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Integer[] counts(UUID listing) {
        return jdbc.queryForObject("SELECT available_quantity, quarantined_quantity FROM listing WHERE id=?",
            (rs, rowNum) -> new Integer[]{rs.getInt(1), rs.getInt(2)}, listing.toString());
    }
}
