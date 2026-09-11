package com.example.campusmarket.catalog.domain;

import com.example.campusmarket.shared.Money;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Listing {
    private final UUID id;
    private final UUID sellerId;
    private final String title;
    private final String description;
    private final String category;
    private final Money unitPrice;
    private final WarrantyTerm warrantyTerm;
    private final Set<UUID> mediaIds = new LinkedHashSet<>();
    private String manufacturerWarrantyProofSnapshot;
    private Instant manufacturerWarrantyExpiresAt;
    private int availableQuantity;
    private int quarantinedQuantity;
    private long version;
    private ListingStatus status;

    private Listing(UUID id, UUID sellerId, String title, String description, String category,
                    Money unitPrice, int availableQuantity, WarrantyTerm warrantyTerm) {
        this.id = Objects.requireNonNull(id, "商品ID不能为空");
        this.sellerId = Objects.requireNonNull(sellerId, "卖家不能为空");
        this.title = requireText(title, "标题");
        this.description = requireText(description, "描述");
        this.category = requireText(category, "分类");
        this.unitPrice = Objects.requireNonNull(unitPrice, "单价不能为空");
        this.warrantyTerm = Objects.requireNonNull(warrantyTerm, "质保声明不能为空");
        if (availableQuantity <= 0) {
            throw new IllegalArgumentException("库存必须为正数");
        }
        this.availableQuantity = availableQuantity;
        this.status = ListingStatus.DRAFT;
    }

    public static Listing draft(UUID sellerId, String title, Money unitPrice, int quantity,
                                WarrantyTerm warrantyTerm) {
        return new Listing(UUID.randomUUID(), sellerId, title, "待补充", "未分类", unitPrice, quantity, warrantyTerm);
    }

    public static Listing draft(UUID sellerId, String title, String description, String category,
                                Money unitPrice, int quantity, WarrantyTerm warrantyTerm) {
        return new Listing(UUID.randomUUID(), sellerId, title, description, category, unitPrice, quantity, warrantyTerm);
    }

    public static Listing reconstitute(UUID id, UUID sellerId, String title, String description, String category,
                                       Money unitPrice, int availableQuantity, int quarantinedQuantity,
                                       WarrantyTerm warrantyTerm, String manufacturerProof,
                                       Instant manufacturerExpiresAt, ListingStatus status, long version,
                                       Set<UUID> mediaIds) {
        Listing listing = new Listing(id, sellerId, title, description, category, unitPrice,
            Math.max(availableQuantity, 1), warrantyTerm);
        if (availableQuantity < 0 || quarantinedQuantity < 0 || version < 0) {
            throw new IllegalArgumentException("库存和版本不能为负数");
        }
        listing.availableQuantity = availableQuantity;
        listing.quarantinedQuantity = quarantinedQuantity;
        listing.manufacturerWarrantyProofSnapshot = manufacturerProof;
        listing.manufacturerWarrantyExpiresAt = manufacturerExpiresAt;
        listing.status = Objects.requireNonNull(status, "商品状态不能为空");
        listing.version = version;
        listing.mediaIds.addAll(mediaIds == null ? Set.of() : mediaIds);
        return listing;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }

    public void addMedia(UUID mediaId) {
        Objects.requireNonNull(mediaId, "媒体ID不能为空");
        if (mediaIds.size() >= 9 && !mediaIds.contains(mediaId)) {
            throw new IllegalStateException("每个商品最多添加9张媒体");
        }
        mediaIds.add(mediaId);
    }

    public Listing publish() {
        if (mediaIds.isEmpty()) {
            throw new IllegalStateException("商品至少需要一张媒体");
        }
        if (availableQuantity <= 0) {
            throw new IllegalStateException("商品没有可售库存");
        }
        if (status != ListingStatus.DRAFT && status != ListingStatus.OFF_SALE && status != ListingStatus.SOLD_OUT) {
            throw new IllegalStateException("当前状态不能发布");
        }
        status = ListingStatus.ON_SALE;
        version++;
        return this;
    }

    public void takeOffSale() {
        if (status != ListingStatus.ON_SALE && status != ListingStatus.SOLD_OUT) {
            throw new IllegalStateException("当前状态不能下架");
        }
        status = ListingStatus.OFF_SALE;
        version++;
    }

    public void setManufacturerWarranty(String proofSnapshot, Instant expiresAt) {
        if (proofSnapshot == null || proofSnapshot.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("厂家质保凭证摘要和到期日不能为空");
        }
        this.manufacturerWarrantyProofSnapshot = proofSnapshot.trim();
        this.manufacturerWarrantyExpiresAt = expiresAt;
    }

    public UUID id() { return id; }
    public UUID sellerId() { return sellerId; }
    public String title() { return title; }
    public String description() { return description; }
    public String category() { return category; }
    public Money unitPrice() { return unitPrice; }
    public int availableQuantity() { return availableQuantity; }
    public int quarantinedQuantity() { return quarantinedQuantity; }
    public WarrantyTerm warrantyTerm() { return warrantyTerm; }
    public String manufacturerWarrantyProofSnapshot() { return manufacturerWarrantyProofSnapshot; }
    public Instant manufacturerWarrantyExpiresAt() { return manufacturerWarrantyExpiresAt; }
    public ListingStatus status() { return status; }
    public long version() { return version; }
    public Set<UUID> mediaIds() { return Collections.unmodifiableSet(mediaIds); }
}
