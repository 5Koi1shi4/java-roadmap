package com.example.campusmarket.catalog.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Elasticsearch 商品索引实现，所有写入均通过 external_gte 版本保护。 */
@Component
public final class ElasticsearchProductSearch implements ProductSearchPort {
    private static final String INITIAL_INDEX = "campus-listing-000001";
    private final ElasticsearchClient client;
    private volatile boolean initialized;

    public ElasticsearchProductSearch(ElasticsearchClient client) {
        this.client = Objects.requireNonNull(client, "Elasticsearch 客户端不能为空");
    }

    @Override
    public void index(ProductDocument document) {
        initializeIfNeeded();
        Objects.requireNonNull(document, "商品文档不能为空");
        try {
            client.index(i -> i.index(WRITE_ALIAS).id(document.listingId())
                .version(document.aggregateVersion()).versionType(VersionType.ExternalGte)
                .document(toMap(document)));
        } catch (IOException e) {
            throw new SearchUnavailableException("商品索引写入失败", e);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (!isVersionConflict(e)) throw e;
        }
    }

    @Override
    public void tombstone(String listingId, long aggregateVersion) {
        initializeIfNeeded();
        if (listingId == null || listingId.isBlank() || aggregateVersion <= 0) {
            throw new IllegalArgumentException("删除版本无效");
        }
        try {
            var response = client.delete(d -> d.index(WRITE_ALIAS).id(listingId)
                .version(aggregateVersion).versionType(VersionType.ExternalGte));
            if (response.result() == Result.NotFound) {
                // ES returns not_found for a missing document while still accepting the external version.
                // A missing document has no tombstone in older ES versions; external_gte fencing still
                // prevents an already-versioned document from being overwritten by a stale event.
            }
        } catch (IOException e) {
            throw new SearchUnavailableException("商品索引删除失败", e);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (!isVersionConflict(e)) throw e;
        }
    }

    @Override
    public SearchPage search(SearchRequest request) {
        initializeIfNeeded();
        Objects.requireNonNull(request, "搜索请求不能为空");
        try {
            Query query = query(request);
            SearchResponse<Map> response = client.search(s -> s.index(READ_ALIAS)
                .query(query)
                .from(Math.multiplyExact(request.page(), request.size()))
                .size(request.size())
                .trackTotalHits(t -> t.enabled(true))
                // Score is ordered first; listing ID is a deterministic tie-breaker.
                .sort(sort -> sort.score(sc -> sc.order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                .sort(sort -> sort.field(f -> f.field("listingId")
                    .order(co.elastic.clients.elasticsearch._types.SortOrder.Asc))), Map.class);
            List<SearchItem> items = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) items.add(fromMap(hit.source()));
            long total = response.hits().total() == null ? items.size() : response.hits().total().value();
            return new SearchPage(items, total, null);
        } catch (IOException | ArithmeticException e) {
            throw new SearchUnavailableException("商品搜索失败", e);
        }
    }

    @Override
    public void refresh() {
        initializeIfNeeded();
        try {
            client.indices().refresh(r -> r.index(WRITE_ALIAS));
        } catch (IOException e) {
            throw new SearchUnavailableException("商品索引刷新失败", e);
        }
    }

    public void refreshIndex(String index) {
        try {
            client.indices().refresh(r -> r.index(index));
        } catch (IOException e) {
            throw new SearchUnavailableException("重建索引刷新失败", e);
        }
    }

    /** 重建服务使用的别名切换入口，动作在一个 aliases 请求中原子完成。 */
    public void switchAliases(String targetIndex, String previousIndex) {
        initializeIfNeeded();
        if (targetIndex == null || targetIndex.isBlank()) throw new IllegalArgumentException("目标索引不能为空");
        try {
            client.indices().updateAliases(a -> {
                if (previousIndex != null && !previousIndex.isBlank()) {
                    a.actions(action -> action.remove(r -> r.index(previousIndex).alias(READ_ALIAS)));
                    a.actions(action -> action.remove(r -> r.index(previousIndex).alias(WRITE_ALIAS)));
                }
                a.actions(action -> action.add(x -> x.index(targetIndex).alias(READ_ALIAS)));
                a.actions(action -> action.add(x -> x.index(targetIndex).alias(WRITE_ALIAS).isWriteIndex(true)));
                return a;
            });
        } catch (IOException e) {
            throw new SearchUnavailableException("搜索别名切换失败", e);
        }
    }

    public String currentReadIndex() {
        initializeIfNeeded();
        try {
            var response = client.indices().getAlias(g -> g.name(READ_ALIAS));
            return response.result().keySet().stream().findFirst().orElse(null);
        } catch (IOException e) {
            throw new SearchUnavailableException("读取搜索别名失败", e);
        }
    }

    public String createRebuildIndex() {
        initializeIfNeeded();
        String index = "campus-listing-rebuild-" + UUID.randomUUID().toString().replace("-", "");
        try {
            client.indices().create(c -> c.index(index).settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                .mappings(m -> m.properties("listingId", p -> p.keyword(k -> k))
                    .properties("title", p -> p.text(t -> t.analyzer("smartcn").searchAnalyzer("smartcn")))
                    .properties("description", p -> p.text(t -> t.analyzer("smartcn").searchAnalyzer("smartcn")))
                    .properties("category", p -> p.keyword(k -> k))
                    .properties("unitPriceFen", p -> p.long_(l -> l))
                    .properties("availableQuantity", p -> p.integer(i -> i))
                    .properties("status", p -> p.keyword(k -> k))
                    .properties("aggregateVersion", p -> p.long_(l -> l))));
            return index;
        } catch (IOException e) {
            throw new SearchUnavailableException("创建重建索引失败", e);
        }
    }

    public void indexInto(String index, ProductDocument document) {
        try {
            client.index(i -> i.index(index).id(document.listingId()).version(document.aggregateVersion())
                .versionType(VersionType.ExternalGte).document(toMap(document)));
        } catch (IOException e) {
            throw new SearchUnavailableException("重建索引写入失败", e);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (!isVersionConflict(e)) throw e;
        }
    }

    public void tombstoneInto(String index, String listingId, long aggregateVersion) {
        try {
            client.delete(i -> i.index(index).id(listingId).version(aggregateVersion)
                .versionType(VersionType.ExternalGte));
        } catch (IOException e) {
            throw new SearchUnavailableException("重建索引删除失败", e);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (!isVersionConflict(e)) throw e;
        }
    }

    private void ensureInitialIndex() {
        try {
            boolean exists = client.indices().exists(e -> e.index(INITIAL_INDEX)).value();
            if (!exists) {
                createIndex(INITIAL_INDEX);
                client.indices().updateAliases(a -> a.actions(x -> x.add(v -> v.index(INITIAL_INDEX).alias(READ_ALIAS)))
                    .actions(x -> x.add(v -> v.index(INITIAL_INDEX).alias(WRITE_ALIAS).isWriteIndex(true))));
            } else {
                ensureAlias(INITIAL_INDEX, READ_ALIAS, false);
                ensureAlias(INITIAL_INDEX, WRITE_ALIAS, true);
            }
        } catch (IOException e) {
            throw new SearchUnavailableException("初始化搜索索引失败", e);
        }
    }

    private void initializeIfNeeded() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            ensureInitialIndex();
            initialized = true;
        }
    }

    private void createIndex(String index) throws IOException {
        client.indices().create(c -> c.index(index)
            .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
            .mappings(m -> m.properties("listingId", p -> p.keyword(k -> k))
                .properties("title", p -> p.text(t -> t.analyzer("smartcn").searchAnalyzer("smartcn")))
                .properties("description", p -> p.text(t -> t.analyzer("smartcn").searchAnalyzer("smartcn")))
                .properties("category", p -> p.keyword(k -> k))
                .properties("unitPriceFen", p -> p.long_(l -> l))
                .properties("availableQuantity", p -> p.integer(i -> i))
                .properties("status", p -> p.keyword(k -> k))
                .properties("aggregateVersion", p -> p.long_(l -> l))));
    }

    private void ensureAlias(String index, String alias, boolean write) throws IOException {
        try {
            client.indices().getAlias(g -> g.name(alias));
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException missing) {
            client.indices().updateAliases(a -> a.actions(x -> x.add(v -> v.index(index).alias(alias).isWriteIndex(write))));
        }
    }

    private static Map<String, Object> toMap(ProductDocument d) {
        Map<String, Object> map = new HashMap<>();
        map.put("listingId", d.listingId()); map.put("title", d.title()); map.put("description", d.description());
        map.put("category", d.category()); map.put("unitPriceFen", d.unitPriceFen());
        map.put("availableQuantity", d.availableQuantity()); map.put("status", d.status());
        map.put("aggregateVersion", d.aggregateVersion());
        return map;
    }

    private static boolean isVersionConflict(co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
        return e.status() == 409 || (e.getMessage() != null && e.getMessage().contains("version_conflict"));
    }

    private static Query query(SearchRequest request) {
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("status").value("ON_SALE"))));
        filters.add(Query.of(q -> q.range(r -> r.untyped(n -> n.field("availableQuantity").gt(JsonData.of(0))))));
        if (request.category() != null) filters.add(Query.of(q -> q.term(t -> t.field("category").value(request.category()))));
        if (request.minPriceFen() != null) filters.add(Query.of(q -> q.range(r -> r.untyped(n -> n.field("unitPriceFen").gte(JsonData.of(request.minPriceFen()))))));
        if (request.maxPriceFen() != null) filters.add(Query.of(q -> q.range(r -> r.untyped(n -> n.field("unitPriceFen").lte(JsonData.of(request.maxPriceFen()))))));
        if (request.keyword().isEmpty()) return Query.of(q -> q.bool(b -> b.filter(filters)));
        return Query.of(q -> q.bool(b -> b.filter(filters).must(m -> m.multiMatch(mm -> mm.query(request.keyword())
            .fields("title", "description").analyzer("smartcn")))));
    }

    @SuppressWarnings("unchecked")
    private static SearchItem fromMap(Map source) {
        if (source == null) throw new SearchUnavailableException("搜索文档为空");
        return new SearchItem(String.valueOf(source.get("listingId")), String.valueOf(source.get("title")),
            String.valueOf(source.get("description")), String.valueOf(source.get("category")),
            ((Number) source.get("unitPriceFen")).longValue(), ((Number) source.get("availableQuantity")).intValue(),
            String.valueOf(source.get("status")), ((Number) source.get("aggregateVersion")).longValue());
    }

    public static final class SearchUnavailableException extends RuntimeException {
        public SearchUnavailableException(String message, Throwable cause) { super(message, cause); }
        public SearchUnavailableException(String message) { super(message); }
    }
}
