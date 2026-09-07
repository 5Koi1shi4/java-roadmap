package com.example.campusmarket.catalog.application;

import com.example.campusmarket.catalog.domain.Listing;
import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.catalog.search.SearchOutboxRepository;
import com.example.campusmarket.storage.ObjectUploadCoordinator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.UUID;

@Service
public class ListingService {
    private final ListingRepository listings;
    private final ObjectUploadCoordinator uploads;
    private final SearchOutboxRepository searchOutbox;
    private final JdbcTemplate jdbc;

    public ListingService(ListingRepository listings, ObjectUploadCoordinator uploads, SearchOutboxRepository searchOutbox) {
        this(listings, uploads, searchOutbox, null);
    }

    @Autowired
    public ListingService(ListingRepository listings, ObjectUploadCoordinator uploads, SearchOutboxRepository searchOutbox, JdbcTemplate jdbc) {
        this.listings = listings;
        this.uploads = uploads;
        this.searchOutbox = java.util.Objects.requireNonNull(searchOutbox, "搜索 Outbox 不能为空");
        this.jdbc = jdbc;
    }

    @Transactional
    public Listing createDraft(UUID sellerId, String title, String description, String category,
                               long unitPriceFen, int quantity, Integer sellerWarrantyDays) {
        Listing listing = Listing.draft(sellerId, title, description, category,
            com.example.campusmarket.shared.Money.ofFen(unitPriceFen), quantity,
            sellerWarrantyDays == null ? WarrantyTerm.none() : WarrantyTerm.sellerWarrantyDays(sellerWarrantyDays));
        return listings.save(listing);
    }

    @Transactional
    public Listing publish(UUID sellerId, UUID listingId) {
        if (activeRestrictionForUpdate(sellerId, "PUBLISH")) throw new RestrictionException();
        Listing listing = owned(sellerId, listingId);
        if (!listings.hasMedia(listingId)) throw new IllegalStateException("商品至少需要一张媒体");
        listing.publish();
        Listing saved = listings.save(listing);
        searchOutbox.enqueue(saved, "LISTING_PUBLISHED");
        return saved;
    }

    @Transactional
    public void takeOffSale(UUID sellerId, UUID listingId) {
        Listing listing = owned(sellerId, listingId);
        listing.takeOffSale();
        listings.save(listing);
        searchOutbox.enqueue(listing, "LISTING_OFF_SALE");
    }

    public ListingRepository.MediaRecord addMedia(UUID sellerId, UUID listingId, String filename,
                                                   String contentType, InputStream input) {
        owned(sellerId, listingId);
        return uploads.uploadListingMedia(listingId, sellerId, filename, contentType, input);
    }

    public MediaContent openMedia(UUID viewerId, UUID listingId, UUID mediaId) {
        Listing listing = listings.findById(listingId).orElseThrow(NotFoundException::new);
        if (!listing.sellerId().equals(viewerId) && listing.status() != com.example.campusmarket.catalog.domain.ListingStatus.ON_SALE) {
            throw new NotFoundException();
        }
        ListingRepository.MediaRecord media = listings.findMedia(listingId, mediaId).orElseThrow(NotFoundException::new);
        return new MediaContent(media.mediaType(), media.sizeBytes(), media.objectKey());
    }

    public boolean canPublish(UUID sellerId) { return !isRestricted(sellerId, "PUBLISH"); }
    public boolean canWithdraw(UUID sellerId) { return !isRestricted(sellerId, "WITHDRAW"); }
    /** Withdrawal command gate; callers must use this command rather than treating canWithdraw as authorization. */
    @Transactional
    public void withdraw(UUID sellerId, long amountFen) {
        if (sellerId == null || amountFen <= 0) throw new IllegalArgumentException("提现金额必须为正数");
        if (activeRestrictionForUpdate(sellerId, "WITHDRAW")) throw new RestrictionException();
    }

    private boolean isRestricted(UUID sellerId, String type) {
        if (jdbc == null || sellerId == null) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM seller_account_restriction WHERE seller_id=? AND restriction_type=? AND status='ACTIVE'", Integer.class, sellerId.toString(), type);
        return n != null && n > 0;
    }
    private boolean activeRestrictionForUpdate(UUID sellerId, String type) {
        if (jdbc == null || sellerId == null) return false;
        return jdbc.query("SELECT source_obligation_id FROM seller_account_restriction WHERE seller_id=? AND restriction_type=? AND status='ACTIVE' FOR UPDATE", (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), sellerId.toString(), type);
    }

    private Listing owned(UUID sellerId, UUID listingId) {
        Listing listing = listings.findById(listingId).orElseThrow(NotFoundException::new);
        if (!listing.sellerId().equals(sellerId)) throw new NotFoundException();
        return listing;
    }

    public record MediaContent(String mediaType, long sizeBytes, String objectKey) { }
    public static class NotFoundException extends RuntimeException { }
    public static class RestrictionException extends RuntimeException { }
}
