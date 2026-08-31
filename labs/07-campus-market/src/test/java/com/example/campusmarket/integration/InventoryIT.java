package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.catalog.application.InventoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    @Test
    void concurrentDeductNeverCreatesNegativeInventory() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            seller.toString(), seller + "@stu.example.edu.cn", "hash");
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,quarantined_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'ON_SALE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材", 100, 5, 0);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            var results = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> inventory.deduct(listing, 1, "order-" + i), pool))
                .toList();
            long successful = 0;
            for (var result : results) if (result.get(20, TimeUnit.SECONDS)) successful++;
            assertThat(successful).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE listing_id=?", Integer.class, listing.toString())).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void insufficientDeductLeavesNoMovement() {
        UUID seller = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user (id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            seller.toString(), seller + "@stu.example.edu.cn", "hash");
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,quarantined_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'ON_SALE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材", 100, 1, 0);
        assertThat(inventory.deduct(listing, 2, "too-many")).isFalse();
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE business_key='too-many'", Integer.class)).isZero();
    }

    private Integer[] counts(UUID listing) {
        return jdbc.queryForObject("SELECT available_quantity, quarantined_quantity FROM listing WHERE id=?",
            (rs, rowNum) -> new Integer[]{rs.getInt(1), rs.getInt(2)}, listing.toString());
    }
}
