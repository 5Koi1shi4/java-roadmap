package com.example.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.example.search.application.search.ProductSearchCriteria;
import com.example.search.application.search.ProductSearchGateway;
import com.example.search.application.search.ProductSearchResult;
import com.example.search.application.search.CategoryBucket;
import com.example.search.application.search.SearchProductHit;
import com.example.search.application.search.SearchUnavailableException;
import com.example.search.domain.ProductStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ElasticsearchProductSearchGateway implements ProductSearchGateway {
    private final ElasticsearchClient client;

    public ElasticsearchProductSearchGateway(ElasticsearchClient client) { this.client = client; }

    @Override
    public ProductSearchResult search(ProductSearchCriteria criteria) {
        if (criteria == null) throw new IllegalArgumentException("criteria is required");
        try {
            SearchResponse<Map> response = client.search(s -> {
                s.index("products-read")
                        .query(query(criteria))
                        .from(criteria.offset())
                        .size(criteria.size())
                        .trackTotalHits(t -> t.enabled(true))
                        .highlight(h -> h.preTags("<em>").postTags("</em>")
                                .fields("name", f -> f)
                                .fields("subtitle", f -> f)
                                .fields("description", f -> f))
                        .aggregations("categories", a -> a.terms(t -> t.field("categoryCode").size(100))
                                .aggregations("name", sub -> sub.terms(t -> t.field("categoryName").size(1))));
                applySort(s, criteria);
                return s;
            }, Map.class);
            List<SearchProductHit> items = response.hits().hits().stream()
                    .map(hit -> toItem(hit.source(), hit.highlight()))
                    .toList();
            return new ProductSearchResult(items, response.hits().total() == null ? items.size()
                    : response.hits().total().value(), categories(response.aggregations().get("categories")));
        } catch (IOException | RuntimeException e) {
            throw new SearchUnavailableException(e);
        }
    }

    private Query query(ProductSearchCriteria criteria) {
        Query text = criteria.keyword().<Query>map(value -> Query.of(q -> q.multiMatch(m -> m
                        .query(value).fields("name^4", "subtitle^2", "description"))))
                .orElseGet(() -> Query.of(q -> q.matchAll(m -> m)));
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("status").value("ON_SALE"))));
        criteria.categoryCode().ifPresent(code -> filters.add(Query.of(q -> q.term(t -> t.field("categoryCode").value(code)))));
        if (criteria.minPrice().isPresent() || criteria.maxPrice().isPresent()) {
            filters.add(Query.of(q -> q.range(r -> r.number(n -> {
                n.field("price");
                criteria.minPrice().ifPresent(value -> n.gte(value.doubleValue()));
                criteria.maxPrice().ifPresent(value -> n.lte(value.doubleValue()));
                return n;
            }))));
        }
        return Query.of(q -> q.bool(b -> b.must(text).filter(filters)));
    }

    private void applySort(co.elastic.clients.elasticsearch.core.SearchRequest.Builder request,
                           ProductSearchCriteria criteria) {
        switch (criteria.sort()) {
            case RELEVANCE -> {
                if (criteria.keyword().isPresent()) request.sort(s -> s.score(sc -> sc.order(SortOrder.Desc)));
                request.sort(s -> s.field(f -> f.field("updatedAt").order(SortOrder.Desc)));
                request.sort(s -> s.field(f -> f.field("productId").order(SortOrder.Asc)));
            }
            case PRICE_ASC -> {
                request.sort(s -> s.field(f -> f.field("price").order(SortOrder.Asc)));
                request.sort(s -> s.field(f -> f.field("productId").order(SortOrder.Asc)));
            }
            case PRICE_DESC -> {
                request.sort(s -> s.field(f -> f.field("price").order(SortOrder.Desc)));
                request.sort(s -> s.field(f -> f.field("productId").order(SortOrder.Asc)));
            }
            case NEWEST -> {
                request.sort(s -> s.field(f -> f.field("updatedAt").order(SortOrder.Desc)));
                request.sort(s -> s.field(f -> f.field("productId").order(SortOrder.Asc)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static SearchProductHit toItem(Map source, Map<String, List<String>> highlights) {
        if (source == null) throw new IllegalStateException("search document is missing");
        return new SearchProductHit(number(source, "productId").longValue(), text(source, "name"),
                (String) source.get("subtitle"), text(source, "description"), text(source, "categoryCode"),
                text(source, "categoryName"), decimal(source.get("price")), ProductStatus.valueOf(text(source, "status")),
                number(source, "sourceVersion").longValue(), instant(source.get("createdAt")), instant(source.get("updatedAt")),
                highlights == null ? Map.of() : new LinkedHashMap<>(highlights));
    }

    @SuppressWarnings("unchecked")
    private static List<CategoryBucket> categories(Aggregate aggregate) {
        if (aggregate == null || !aggregate.isSterms()) return List.of();
        List<CategoryBucket> result = new ArrayList<>();
        for (var bucket : aggregate.sterms().buckets().array()) {
            String name = bucket.key().stringValue();
            Aggregate sub = bucket.aggregations().get("name");
            if (sub != null && sub.isSterms() && !sub.sterms().buckets().array().isEmpty()) {
                name = sub.sterms().buckets().array().get(0).key().stringValue();
            }
            result.add(new CategoryBucket(bucket.key().stringValue(), name, bucket.docCount()));
        }
        return result;
    }

    private static String text(Map source, String key) {
        Object value = source.get(key);
        return value == null ? "" : value.toString();
    }
    private static Number number(Map source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Number number)) throw new IllegalStateException("search document field is invalid");
        return number;
    }
    private static BigDecimal decimal(Object value) {
        if (!(value instanceof Number number)) throw new IllegalStateException("search document price is invalid");
        return new BigDecimal(value.toString());
    }
    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse(value.toString());
    }
}
