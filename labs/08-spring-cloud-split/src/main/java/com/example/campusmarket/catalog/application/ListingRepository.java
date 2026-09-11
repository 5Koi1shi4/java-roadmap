package com.example.campusmarket.catalog.application;

import com.example.campusmarket.catalog.domain.Listing;

import java.util.Optional;
import java.util.UUID;

public interface ListingRepository {
    Listing save(Listing listing);
    Optional<Listing> findById(UUID id);
    boolean hasMedia(UUID listingId);
    void saveMedia(UUID listingId, UUID mediaId, String objectKey, String mediaType, long sizeBytes, int sortOrder);
    Optional<MediaRecord> findMedia(UUID listingId, UUID mediaId);

    record MediaRecord(UUID id, UUID listingId, String objectKey, String mediaType, long sizeBytes, int sortOrder) { }
}
