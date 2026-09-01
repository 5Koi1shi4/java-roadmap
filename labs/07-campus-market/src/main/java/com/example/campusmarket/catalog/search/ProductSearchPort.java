package com.example.campusmarket.catalog.search;

import java.util.List;
import java.util.Objects;

/** 商品搜索的应用端口；搜索索引不是商品事实源。 */
public interface ProductSearchPort {
    String READ_ALIAS = "campus-listing-read";
    String WRITE_ALIAS = "campus-listing-write";

    void index(ProductDocument document);

    default void indexInto(String index, ProductDocument document) { index(document); }

    void tombstone(String listingId, long aggregateVersion);

    default void tombstoneInto(String index, String listingId, long aggregateVersion) {
        tombstone(listingId, aggregateVersion);
    }

    SearchPage search(SearchRequest request);

    void refresh();

    record ProductDocument(String listingId, String title, String description, String category,
                           long unitPriceFen, int availableQuantity, String status, long aggregateVersion) {
        public ProductDocument {
            if (listingId == null || listingId.isBlank()) throw new IllegalArgumentException("商品ID不能为空");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("标题不能为空");
            if (description == null) throw new IllegalArgumentException("描述不能为空");
            if (category == null || category.isBlank()) throw new IllegalArgumentException("分类不能为空");
            if (unitPriceFen < 0 || availableQuantity < 0 || aggregateVersion <= 0) {
                throw new IllegalArgumentException("商品搜索字段无效");
            }
            Objects.requireNonNull(status, "商品状态不能为空");
        }
    }

    record SearchRequest(String keyword, String category, Long minPriceFen, Long maxPriceFen,
                         int page, int size) {
        public SearchRequest {
            keyword = keyword == null ? "" : keyword.trim();
            category = category == null || category.isBlank() ? null : category.trim();
            if (minPriceFen != null && minPriceFen < 0) throw new IllegalArgumentException("最低价格不能为负数");
            if (maxPriceFen != null && maxPriceFen < 0) throw new IllegalArgumentException("最高价格不能为负数");
            if (minPriceFen != null && maxPriceFen != null && minPriceFen > maxPriceFen) {
                throw new IllegalArgumentException("价格范围无效");
            }
            if (page < 0 || size <= 0 || size > 100) throw new IllegalArgumentException("分页参数无效");
        }
    }

    record SearchItem(String listingId, String title, String description, String category,
                      long unitPriceFen, int availableQuantity, String status, long aggregateVersion) { }

    record SearchPage(List<SearchItem> items, long total, String nextSearchAfter) {
        public SearchPage {
            items = List.copyOf(Objects.requireNonNull(items, "搜索结果不能为空"));
            if (total < 0) throw new IllegalArgumentException("总数不能为负数");
        }
    }
}
