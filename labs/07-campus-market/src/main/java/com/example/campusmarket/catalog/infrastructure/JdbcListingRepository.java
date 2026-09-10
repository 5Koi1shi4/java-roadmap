package com.example.campusmarket.catalog.infrastructure;

import com.example.campusmarket.catalog.application.ListingRepository;
import com.example.campusmarket.catalog.domain.Listing;
import com.example.campusmarket.catalog.domain.ListingStatus;
import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.shared.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcListingRepository implements ListingRepository {
    private final JdbcTemplate jdbc;

    public JdbcListingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Listing save(Listing listing) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO listing (id, seller_id, title, description, category, unit_price_fen,
                available_quantity, quarantined_quantity, warranty_days, warranty_scope,
                manufacturer_warranty_proof_snapshot, manufacturer_warranty_expires_at,
                status, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE title=VALUES(title), description=VALUES(description),
                category=VALUES(category), unit_price_fen=VALUES(unit_price_fen),
                available_quantity=VALUES(available_quantity), quarantined_quantity=VALUES(quarantined_quantity),
                warranty_days=VALUES(warranty_days), warranty_scope=VALUES(warranty_scope),
                manufacturer_warranty_proof_snapshot=VALUES(manufacturer_warranty_proof_snapshot),
                manufacturer_warranty_expires_at=VALUES(manufacturer_warranty_expires_at),
                status=VALUES(status), version=VALUES(version), updated_at=VALUES(updated_at)
            """, listing.id().toString(), listing.sellerId().toString(), listing.title(), listing.description(),
            listing.category(), listing.unitPrice().fen(), listing.availableQuantity(), listing.quarantinedQuantity(),
            listing.warrantyTerm().sellerWarrantyDays(), listing.warrantyTerm().sellerWarrantyDays() == null ? null : "SELLER",
            listing.manufacturerWarrantyProofSnapshot(), timestamp(listing.manufacturerWarrantyExpiresAt()),
            listing.status().name(), listing.version(), timestamp(now), timestamp(now));
        return listing;
    }

    @Override
    public Optional<Listing> findById(UUID id) {
        return jdbc.query("SELECT * FROM listing WHERE id = ?", rs -> rs.next()
            ? Optional.of(Listing.reconstitute(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("seller_id")), rs.getString("title"), rs.getString("description"),
                rs.getString("category"), Money.ofFen(rs.getLong("unit_price_fen")),
                rs.getInt("available_quantity"), rs.getInt("quarantined_quantity"),
                new WarrantyTerm((Integer) rs.getObject("warranty_days")),
                rs.getString("manufacturer_warranty_proof_snapshot"), instant(rs.getTimestamp("manufacturer_warranty_expires_at")),
                ListingStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                new LinkedHashSet<>(jdbc.query("SELECT id FROM listing_media WHERE listing_id = ? ORDER BY sort_order",
                    (mediaRs, rowNum) -> UUID.fromString(mediaRs.getString("id")), id.toString()))))
            : Optional.empty(), id.toString());
    }

    @Override
    public boolean hasMedia(UUID listingId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM listing_media WHERE listing_id = ?", Integer.class, listingId.toString());
        return count != null && count > 0;
    }

    @Override
    public void saveMedia(UUID listingId, UUID mediaId, String objectKey, String mediaType, long sizeBytes, int sortOrder) {
        jdbc.update("INSERT INTO listing_media (id, listing_id, object_key, media_type, size_bytes, sort_order, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            mediaId.toString(), listingId.toString(), objectKey, mediaType, sizeBytes, sortOrder, timestamp(Instant.now()));
    }

    @Override
    public Optional<MediaRecord> findMedia(UUID listingId, UUID mediaId) {
        return jdbc.query("SELECT id, listing_id, object_key, media_type, size_bytes, sort_order FROM listing_media WHERE listing_id = ? AND id = ?",
            rs -> rs.next() ? Optional.of(new MediaRecord(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("listing_id")), rs.getString("object_key"), rs.getString("media_type"),
                rs.getLong("size_bytes"), rs.getInt("sort_order"))) : Optional.empty(), listingId.toString(), mediaId.toString());
    }

    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
