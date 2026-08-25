package com.example.search.application.search;

import java.math.BigDecimal;
import java.util.Optional;

/** Validated, protocol-independent search criteria. */
public final class ProductSearchCriteria {
    private static final int MAX_WINDOW = 10_000;
    private final String keyword;
    private final String categoryCode;
    private final BigDecimal minPrice;
    private final BigDecimal maxPrice;
    private final int page;
    private final int size;
    private final ProductSort sort;

    private ProductSearchCriteria(String keyword, String categoryCode, BigDecimal minPrice, BigDecimal maxPrice,
                                  int page, int size, ProductSort sort) {
        this.keyword = normalize(keyword);
        this.categoryCode = normalize(categoryCode);
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        if (page < 0) throw new IllegalArgumentException("page must be non-negative");
        if (size < 1 || size > 50) throw new IllegalArgumentException("size must be between 1 and 50");
        if ((long) page * size + size > MAX_WINDOW) throw new IllegalArgumentException("search window exceeds 10000");
        if (minPrice != null && minPrice.signum() < 0) throw new IllegalArgumentException("minPrice must be non-negative");
        if (maxPrice != null && maxPrice.signum() < 0) throw new IllegalArgumentException("maxPrice must be non-negative");
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("minPrice must not exceed maxPrice");
        }
        this.page = page;
        this.size = size;
        this.sort = sort == null ? ProductSort.RELEVANCE : sort;
    }

    public static ProductSearchCriteria of(String keyword, String categoryCode, BigDecimal minPrice,
                                           BigDecimal maxPrice, int page, int size, String sort) {
        return new ProductSearchCriteria(keyword, categoryCode, minPrice, maxPrice, page, size, ProductSort.parse(sort));
    }

    public static ProductSearchCriteria of(String keyword, String categoryCode, BigDecimal minPrice,
                                           BigDecimal maxPrice, int page, int size, ProductSort sort) {
        return new ProductSearchCriteria(keyword, categoryCode, minPrice, maxPrice, page, size, sort);
    }

    public Optional<String> keyword() { return Optional.ofNullable(keyword); }
    public Optional<String> categoryCode() { return Optional.ofNullable(categoryCode); }
    public Optional<BigDecimal> minPrice() { return Optional.ofNullable(minPrice); }
    public Optional<BigDecimal> maxPrice() { return Optional.ofNullable(maxPrice); }
    public int page() { return page; }
    public int size() { return size; }
    public int offset() { return page * size; }
    public ProductSort sort() { return sort; }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.codePointCount(0, normalized.length()) > 100) {
            throw new IllegalArgumentException("keyword must be at most 100 Unicode code points");
        }
        return normalized;
    }

}
