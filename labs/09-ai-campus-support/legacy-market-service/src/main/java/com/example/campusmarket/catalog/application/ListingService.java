package com.example.campusmarket.catalog.application;

import com.example.campusmarket.catalog.domain.Listing;
import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.catalog.search.SearchOutboxRepository;
import com.example.campusmarket.storage.ObjectUploadCoordinator;
import com.example.campusmarket.observability.CampusMetrics;
import com.example.campusmarket.observability.AfterCommitMetrics;
import com.example.campusmarket.shared.infrastructure.SellerBalanceLockRepository;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

@Service
@Profile("!test")
public class ListingService {
    private final ListingRepository listings;
    private final ObjectUploadCoordinator uploads;
    private final SearchOutboxRepository searchOutbox;
    private final JdbcTemplate jdbc;
    private final CampusMetrics metrics;
    private final SellerBalanceLockRepository sellerLocks;

    public ListingService(ListingRepository listings, ObjectUploadCoordinator uploads, SearchOutboxRepository searchOutbox) {
        this(listings, uploads, searchOutbox, null, null, null);
    }

    /** 兼容仍直接提供加锁前依赖的调用方。 */
    public ListingService(ListingRepository listings, ObjectUploadCoordinator uploads, SearchOutboxRepository searchOutbox, JdbcTemplate jdbc,
                          CampusMetrics metrics) {
        this(listings, uploads, searchOutbox, jdbc, metrics,
            jdbc == null ? null : new SellerBalanceLockRepository(jdbc));
    }

    @Autowired
    public ListingService(ListingRepository listings, ObjectUploadCoordinator uploads, SearchOutboxRepository searchOutbox, JdbcTemplate jdbc,
                          CampusMetrics metrics, SellerBalanceLockRepository sellerLocks) {
        this.listings = listings;
        this.uploads = uploads;
        this.searchOutbox = java.util.Objects.requireNonNull(searchOutbox, "搜索 Outbox 不能为空");
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.sellerLocks = sellerLocks;
    }

    @Transactional
    public Listing createDraft(UUID sellerId, String title, String description, String category,
                               long unitPriceFen, int quantity, Integer sellerWarrantyDays) {
        return createDraft(sellerId, title, description, category, unitPriceFen, quantity,
            sellerWarrantyDays, null, null);
    }

    @Transactional
    public Listing createDraft(UUID sellerId, String title, String description, String category,
                               long unitPriceFen, int quantity, Integer sellerWarrantyDays,
                               String manufacturerWarrantyProofSnapshot,
                               Instant manufacturerWarrantyExpiresAt) {
        Listing listing = Listing.draft(sellerId, title, description, category,
            com.example.campusmarket.shared.Money.ofFen(unitPriceFen), quantity,
            sellerWarrantyDays == null ? WarrantyTerm.none() : WarrantyTerm.sellerWarrantyDays(sellerWarrantyDays));
        if (manufacturerWarrantyProofSnapshot != null || manufacturerWarrantyExpiresAt != null) {
            listing.setManufacturerWarranty(manufacturerWarrantyProofSnapshot, manufacturerWarrantyExpiresAt);
        }
        return listings.save(listing);
    }

    @Transactional
    public Listing publish(UUID sellerId, UUID listingId) {
        try {
            if (activeRestrictionForUpdate(sellerId, "PUBLISH")) throw new RestrictionException();
            Listing listing = owned(sellerId, listingId);
            if (!listings.hasMedia(listingId)) throw new IllegalStateException("商品至少需要一张媒体");
            listing.publish();
            Listing saved = listings.save(listing);
            searchOutbox.enqueue(saved.id(), saved.version(), "LISTING_PUBLISHED");
            if (metrics != null) AfterCommitMetrics.record(() -> metrics.recordListingPublished("SUCCESS"));
            return saved;
        } catch (RestrictionException rejected) {
            if (metrics != null) metrics.recordListingPublished("REJECTED");
            throw rejected;
        } catch (RuntimeException failure) {
            if (metrics != null) metrics.recordListingPublished("FAILURE");
            throw failure;
        }
    }

    @Transactional
    public void takeOffSale(UUID sellerId, UUID listingId) {
        Listing listing = owned(sellerId, listingId);
        listing.takeOffSale();
        listings.save(listing);
        searchOutbox.enqueue(listing.id(), listing.version(), "LISTING_OFF_SALE");
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
    /** 可提现命令入口；调用方必须使用此命令，不能把 canWithdraw 当作授权。 */
    @Transactional
    public void withdraw(UUID sellerId, long amountFen) {
        withdraw(sellerId, amountFen, "withdraw-" + UUID.randomUUID());
    }
    @Transactional
    public UUID withdraw(UUID sellerId, long amountFen, String idempotencyKey) {
        if (sellerId == null || amountFen <= 0 || idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("提现请求无效");
        if (jdbc == null) throw new IllegalStateException("提现账户存储不可用");
        if (sellerLocks == null) throw new IllegalStateException("卖家锁存储不可用");
        // 所有改变余额事实的市场事务都必须先锁定此持久行，
        // 再访问幂等、限制或结算事实。
        sellerLocks.lock(sellerId);
        Withdrawal existing=jdbc.query("SELECT id,amount_fen FROM seller_withdrawal WHERE seller_id=? AND idempotency_key=? FOR UPDATE",rs->rs.next()?new Withdrawal(UUID.fromString(rs.getString(1)),rs.getLong(2)):null,sellerId.toString(),idempotencyKey);
        if(existing!=null){if(existing.amount()!=amountFen) throw new IllegalArgumentException("提现幂等冲突");return existing.id();}
        if (activeRestrictionForUpdate(sellerId, "WITHDRAW")) throw new RestrictionException();
        Long available=jdbc.queryForObject("SELECT GREATEST(0,COALESCE((SELECT SUM(s.net_settlement_fen) FROM settlement s JOIN trade_order o ON o.id=s.order_id WHERE o.seller_id=? AND s.status='SETTLED'),0)-COALESCE((SELECT SUM(amount_fen) FROM seller_withdrawal WHERE seller_id=? AND status IN ('REQUESTED','COMPLETED')),0))",Long.class,sellerId.toString(),sellerId.toString());
        if(available==null||amountFen>available) throw new InsufficientBalanceException();
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO seller_withdrawal(id,seller_id,idempotency_key,amount_fen,status,created_at,updated_at) VALUES (?,?,?,?,'REQUESTED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",id.toString(),sellerId.toString(),idempotencyKey,amountFen);
        return id;
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
    public static class InsufficientBalanceException extends RuntimeException { }
    private record Withdrawal(UUID id,long amount) { }
}
