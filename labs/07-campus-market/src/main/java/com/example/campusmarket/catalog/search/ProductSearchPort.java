package com.example.campusmarket.catalog.search;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.Base64;


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
            if (title == null || (title.isBlank() && !SearchSchema.TOMBSTONE.equals(status))) throw new IllegalArgumentException("标题不能为空");
            if (description == null || (description.isBlank() && !SearchSchema.TOMBSTONE.equals(status))) throw new IllegalArgumentException("描述不能为空");
            if (category == null || (category.isBlank() && !SearchSchema.TOMBSTONE.equals(status))) throw new IllegalArgumentException("分类不能为空");
            if (unitPriceFen < 0 || availableQuantity < 0 || aggregateVersion <= 0) {
                throw new IllegalArgumentException("商品搜索字段无效");
            }
            SearchSchema.requireStatus(status);
        }
        public static ProductDocument tombstone(String listingId, long version) {
            return new ProductDocument(listingId, "", "", "", 0, 0, SearchSchema.TOMBSTONE, version);
        }
    }

    record SearchRequest(String keyword, String category, Long minPriceFen, Long maxPriceFen,
                         int page, int size, String searchAfter) {
        public SearchRequest(String keyword, String category, Long minPriceFen, Long maxPriceFen, int page, int size) {
            this(keyword, category, minPriceFen, maxPriceFen, page, size, null);
        }
        public SearchRequest {
            keyword = keyword == null ? "" : keyword.trim();
            category = category == null || category.isBlank() ? null : category.trim();
            if (minPriceFen != null && minPriceFen < 0) throw new IllegalArgumentException("最低价格不能为负数");
            if (maxPriceFen != null && maxPriceFen < 0) throw new IllegalArgumentException("最高价格不能为负数");
            if (minPriceFen != null && maxPriceFen != null && minPriceFen > maxPriceFen) {
                throw new IllegalArgumentException("价格范围无效");
            }
            if (page != 0 || size <= 0 || size > 100) throw new IllegalArgumentException("请使用 search_after 游标分页");
            if (searchAfter != null && searchAfter.isBlank()) throw new IllegalArgumentException("游标不能为空");
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

    record SearchCursor(String pitId, String fingerprint, double score, String listingId) {
        public SearchCursor(double score, String listingId) { this("", "", score, listingId); }
        public SearchCursor {
            if (pitId == null || fingerprint == null || pitId.length() > 500 || fingerprint.length() > 200
                || !Double.isFinite(score) || listingId == null || listingId.isBlank() || listingId.length() > 200
                || listingId.indexOf('\n') >= 0 || listingId.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("搜索游标无效");
            }
        }
    }

    static String encodeCursor(double score, String listingId) {
        SearchCursor cursor = new SearchCursor(score, listingId);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (Double.toHexString(cursor.score()) + "\n" + cursor.listingId()).getBytes(StandardCharsets.UTF_8));
    }

    static String encodeCursor(String pitId, String fingerprint, double score, String listingId) {
        SearchCursor cursor = new SearchCursor(pitId, fingerprint, score, listingId);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (cursor.pitId() + "\n" + cursor.fingerprint() + "\n" + Double.toHexString(cursor.score()) + "\n" + cursor.listingId()).getBytes(StandardCharsets.UTF_8));
    }

    static SearchCursor decodeCursor(String encoded) {
        if (encoded == null || encoded.length() > 1000) throw new IllegalArgumentException("搜索游标无效");
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split("\\n", -1);
            if (parts.length == 2) {
                if (parts[0].isBlank() || parts[1].isBlank()) throw new IllegalArgumentException("搜索游标无效");
                double score = Double.parseDouble(parts[0]);
                if (!Double.toHexString(score).equals(parts[0])) throw new IllegalArgumentException("游标 score 非 canonical");
                return new SearchCursor(score, parts[1]);
            }
            if (parts.length != 4 || parts[0].isBlank() || parts[1].isBlank() || parts[3].isBlank()) throw new IllegalArgumentException("搜索游标无效");
            double score = Double.parseDouble(parts[2]);
            if (!Double.toHexString(score).equals(parts[2])) throw new IllegalArgumentException("游标 score 非 canonical");
            return new SearchCursor(parts[0], parts[1], score, parts[3]);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("搜索游标无效", e);
        }
    }
}
