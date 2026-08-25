package com.example.search.application.search;

import java.util.List;

public record ProductSearchResult(List<SearchProductHit> items, long total, List<CategoryBucket> categories) {
    public ProductSearchResult {
        items = List.copyOf(items == null ? List.of() : items);
        categories = List.copyOf(categories == null ? List.of() : categories);
        if (total < 0) throw new IllegalArgumentException("total must be non-negative");
    }
}
