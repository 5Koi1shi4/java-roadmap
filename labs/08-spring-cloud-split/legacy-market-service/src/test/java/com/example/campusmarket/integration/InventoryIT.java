package com.example.campusmarket.integration;

import com.example.campusmarket.legacy.LegacyMarketApplication;
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

@SpringBootTest(classes = LegacyMarketApplication.class)
@ActiveProfiles("local")
class InventoryIT extends SharedContainers {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryPort inventory;

    @Test
    void quarantineAddsIsolatedStockAndIdempotencyBindsReason() {
        UUID seller = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
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
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,quarantined_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'ON_SALE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材", 100, 1, 0);
        assertThat(inventory.deduct(listing, 2, "too-many")).isFalse();
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM listing WHERE id=?", Integer.class, listing.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE business_key='too-many'", Integer.class)).isZero();
    }

    @Test
    void restoreReturnsAvailableStockAndReopensSoldOutListing() {
        UUID seller = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        insertListing(seller, listing, 1, "ON_SALE");

        assertThat(inventory.deduct(listing, 1, "restore-order")).isTrue();
        assertThat(counts(listing)).containsExactly(0, 0);
        assertThat(status(listing)).isEqualTo("SOLD_OUT");
        assertThat(inventory.restore(listing, 1, "restore-cancel")).isTrue();
        assertThat(counts(listing)).containsExactly(1, 0);
        assertThat(status(listing)).isEqualTo("ON_SALE");
        assertThat(inventory.restore(listing, 1, "restore-cancel")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE listing_id=?", Integer.class, listing.toString())).isEqualTo(2);
    }

    @Test
    void sellerExplicitRelistConsumesQuarantineIdempotently() {
        UUID seller = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        insertListing(seller, listing, 0, "SOLD_OUT");
        assertThat(inventory.quarantine(listing, 1, "return-q-" + listing)).isTrue();
        assertThat(inventory.relistQuarantined(listing, 1, "return-relist-" + listing)).isTrue();
        assertThat(inventory.relistQuarantined(listing, 1, "return-relist-" + listing)).isTrue();
        assertThat(counts(listing)).containsExactly(1, 0);
        assertThat(status(listing)).isEqualTo("ON_SALE");
    }

    @Test
    void sameBusinessKeyAcrossListingsHasOneCommitAndExplicitConflict() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID firstListing = UUID.randomUUID();
        UUID secondListing = UUID.randomUUID();
        insertListing(seller, firstListing, 1, "ON_SALE");
        insertListing(seller, secondListing, 1, "ON_SALE");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = CompletableFuture.supplyAsync(() -> inventory.deduct(firstListing, 1, "cross-listing-key"), pool);
            var second = CompletableFuture.supplyAsync(() -> inventory.deduct(secondListing, 1, "cross-listing-key"), pool);
            int successful = 0;
            var failures = new java.util.ArrayList<Throwable>();
            for (var result : java.util.List.of(first, second)) {
                try {
                    if (result.get(20, TimeUnit.SECONDS)) successful++;
                } catch (java.util.concurrent.ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            assertThat(successful).isEqualTo(1);
            assertThat(failures).singleElement().isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("SELECT SUM(available_quantity) FROM listing WHERE id IN (?,?)", Integer.class,
                firstListing.toString(), secondListing.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE business_key='cross-listing-key'", Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameBusinessKeyWithDifferentOrderIdIsRejected() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        insertUser(seller);
        insertUser(buyer);
        insertListing(seller, listing, 2, "ON_SALE");
        insertOrder(buyer, seller, listing, UUID.randomUUID());
        UUID firstOrder = UUID.randomUUID();
        UUID secondOrder = UUID.randomUUID();
        insertOrder(buyer, seller, listing, firstOrder);
        insertOrder(buyer, seller, listing, secondOrder);

        assertThat(inventory.deduct(listing, 1, "order-key-conflict", firstOrder)).isTrue();
        assertThatThrownBy(() -> inventory.deduct(listing, 1, "order-key-conflict", secondOrder))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Integer[] counts(UUID listing) {
        return jdbc.queryForObject("SELECT available_quantity, quarantined_quantity FROM listing WHERE id=?",
            (rs, rowNum) -> new Integer[]{rs.getInt(1), rs.getInt(2)}, listing.toString());
    }

    private String status(UUID listing) {
        return jdbc.queryForObject("SELECT status FROM listing WHERE id=?", String.class, listing.toString());
    }

    private void insertListing(UUID seller, UUID listing, int quantity, String status) {
        insertUser(seller);
        jdbc.update("INSERT INTO listing (id,seller_id,title,description,category,unit_price_fen,available_quantity,quarantined_quantity,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?, ?,?,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "教材", "描述", "教材", 100, quantity, 0, status);
    }

    private void insertUser(UUID user) {
    }

    private void insertOrder(UUID buyer, UUID seller, UUID listing, UUID order) {
        jdbc.update("""
            INSERT INTO trade_order (id,buyer_id,seller_id,listing_id,listing_title_snapshot,listing_description_snapshot,
                unit_price_fen,quantity,total_amount_fen,paid_amount_fen,status,version,created_at,updated_at)
            VALUES (?,?,?,?,?,?,100,1,100,0,'PENDING_PAYMENT',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """, order.toString(), buyer.toString(), seller.toString(), listing.toString(), "教材", "描述");
    }
}
