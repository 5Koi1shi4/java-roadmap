package com.example.campusmarket.catalog.application;

import com.example.campusmarket.catalog.domain.Listing;
import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.storage.ObjectUploadCoordinator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.UUID;

@Service
public class ListingService {
    private final ListingRepository listings;
    private final ObjectUploadCoordinator uploads;

    public ListingService(ListingRepository listings, ObjectUploadCoordinator uploads) {
        this.listings = listings;
        this.uploads = uploads;
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
        Listing listing = owned(sellerId, listingId);
        if (!listings.hasMedia(listingId)) throw new IllegalStateException("商品至少需要一张媒体");
        listing.publish();
        return listings.save(listing);
    }

    @Transactional
    public void takeOffSale(UUID sellerId, UUID listingId) {
        Listing listing = owned(sellerId, listingId);
        listing.takeOffSale();
        listings.save(listing);
    }

    public ListingRepository.MediaRecord addMedia(UUID sellerId, UUID listingId, String filename,
                                                   String contentType, InputStream input) {
        Listing listing = owned(sellerId, listingId);
        if (listing.mediaIds().size() >= 9) throw new IllegalStateException("每个商品最多添加9张媒体");
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

    private Listing owned(UUID sellerId, UUID listingId) {
        Listing listing = listings.findById(listingId).orElseThrow(NotFoundException::new);
        if (!listing.sellerId().equals(sellerId)) throw new NotFoundException();
        return listing;
    }

    public record MediaContent(String mediaType, long sizeBytes, String objectKey) { }
    public static class NotFoundException extends RuntimeException { }
}
