package com.example.campusmarket.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 读侧商品搜索适配器；索引只保存商品快照，不访问事实服务。 */
@Component
public class ElasticsearchProductSearch implements ProductSearchPort {
    private static final String INITIAL_INDEX = "campus-product-000001";
    private static final String REBUILD_PREFIX = "campus-product-rebuild-";
    private static final String PIT_KEEP_ALIVE = "1m";

    private final ElasticsearchClient client;
    private volatile boolean initialized;

    public ElasticsearchProductSearch(ElasticsearchClient client) {
        this.client = Objects.requireNonNull(client, "Elasticsearch客户端不能为空");
    }

    @Override
    public void index(ProductDocument document) {
        Objects.requireNonNull(document, "商品文档不能为空");
        initializeIfNeeded();
        indexInto(ProductSearchPort.WRITE_ALIAS, document);
    }

    @Override
    public void tombstone(String listingId, long aggregateVersion) {
        if (listingId == null || listingId.isBlank() || aggregateVersion <= 0) {
            throw new IllegalArgumentException("删除版本无效");
        }
        initializeIfNeeded();
        tombstoneInto(ProductSearchPort.WRITE_ALIAS, listingId, aggregateVersion);
    }

    @Override
    public SearchPage search(SearchRequest request) {
        Objects.requireNonNull(request, "搜索请求不能为空");
        initializeIfNeeded();

        ProductSearchPort.SearchCursor cursor = request.searchAfter() == null
                ? null : ProductSearchPort.decodeCursor(request.searchAfter());
        String fingerprint = fingerprint(request);
        if (cursor != null && (cursor.pitId().isBlank() || !fingerprint.equals(cursor.fingerprint()))) {
            throw new IllegalArgumentException("搜索游标与查询条件不匹配");
        }

        String pit = cursor == null ? null : cursor.pitId();
        try {
            if (pit == null) {
                pit = client.openPointInTime(o -> o.index(ProductSearchPort.READ_ALIAS)
                        .keepAlive(time -> time.time(PIT_KEEP_ALIVE))).id();
            }
            String requestPit = pit;
            SearchResponse<Map> response = client.search(s -> {
                s.pit(p -> p.id(requestPit).keepAlive(time -> time.time(PIT_KEEP_ALIVE)))
                        .query(query(request))
                        .size(request.size() + 1)
                        .trackTotalHits(total -> total.enabled(true))
                        .sort(sort -> sort.score(score -> score.order(SortOrder.Desc)))
                        .sort(sort -> sort.field(field -> field.field("listingId").order(SortOrder.Asc)));
                if (cursor != null) {
                    s.searchAfter(searchAfter(cursor));
                }
                return s;
            }, Map.class);

            String responsePit = response.pitId();
            String currentPit = responsePit == null || responsePit.isBlank() ? pit : responsePit;
            List<Hit<Map>> hits = response.hits().hits();
            boolean hasMore = hits.size() > request.size();
            List<SearchItem> items = new ArrayList<>();
            String next = null;
            for (int i = 0; i < Math.min(request.size(), hits.size()); i++) {
                Hit<Map> hit = hits.get(i);
                items.add(fromMap(hit.source()));
                if (hit.sort().size() >= 2) {
                    next = ProductSearchPort.encodeCursor(currentPit, fingerprint,
                            hit.sort().get(0).doubleValue(), hit.sort().get(1).stringValue());
                }
            }

            long total = response.hits().total() == null
                    ? items.size() : response.hits().total().value();
            if (!hasMore) {
                next = null;
                closePit(currentPit);
            } else {
                pit = currentPit;
            }
            return new SearchPage(items, total, next);
        } catch (ProductSearchPort.SearchUnavailableException unavailable) {
            closePit(pit);
            throw unavailable;
        } catch (IllegalArgumentException invalid) {
            closePit(pit);
            throw invalid;
        } catch (IOException failure) {
            closePit(pit);
            throw new SearchUnavailableException("商品搜索失败", failure);
        } catch (RuntimeException failure) {
            closePit(pit);
            throw new SearchUnavailableException("商品搜索失败", failure);
        }
    }

    @Override
    public void refresh() {
        initializeIfNeeded();
        try {
            client.indices().refresh(refresh -> refresh.index(ProductSearchPort.WRITE_ALIAS));
        } catch (IOException | RuntimeException failure) {
            throw new SearchUnavailableException("商品索引刷新失败", failure);
        }
    }

    /** 将商品快照写入指定的重建索引，并保留外部版本保护。 */
    public void indexInto(String index, ProductDocument document) {
        if (index == null || index.isBlank()) {
            throw new IllegalArgumentException("目标索引不能为空");
        }
        Objects.requireNonNull(document, "商品文档不能为空");
        try {
            client.index(request -> request.index(index).id(document.listingId())
                    .version(document.aggregateVersion()).versionType(VersionType.ExternalGte)
                    .document(toMap(document)));
        } catch (IOException failure) {
            if (isVersionConflict(failure)) {
                return;
            }
            throw new SearchUnavailableException("商品索引写入失败", failure);
        } catch (ElasticsearchException failure) {
            if (isVersionConflict(failure)) {
                return;
            }
            throw new SearchUnavailableException("商品索引写入失败", failure);
        } catch (RuntimeException failure) {
            throw new SearchUnavailableException("商品索引写入失败", failure);
        }
    }

    /** 将 tombstone 写入指定的重建索引，并保留外部版本保护。 */
    public void tombstoneInto(String index, String listingId, long aggregateVersion) {
        if (index == null || index.isBlank()) {
            throw new IllegalArgumentException("目标索引不能为空");
        }
        if (listingId == null || listingId.isBlank() || aggregateVersion <= 0) {
            throw new IllegalArgumentException("删除版本无效");
        }
        indexInto(index, ProductDocument.tombstone(listingId, aggregateVersion));
    }

    public void refreshIndex(String index) {
        if (index == null || index.isBlank()) {
            throw new IllegalArgumentException("目标索引不能为空");
        }
        try {
            client.indices().refresh(refresh -> refresh.index(index));
        } catch (IOException | RuntimeException failure) {
            throw new SearchUnavailableException("重建索引刷新失败", failure);
        }
    }

    public String currentReadIndex() {
        Set<String> indexes = currentReadIndexes();
        return indexes.isEmpty() ? null : indexes.iterator().next();
    }

    public Set<String> currentReadIndexes() {
        initializeIfNeeded();
        return aliasMembers(ProductSearchPort.READ_ALIAS, "读取搜索别名失败");
    }

    /** 与重建协调器共享的读别名读取入口。 */
    public Set<String> readCurrentReadIndexes() {
        return currentReadIndexes();
    }

    /** 与重建协调器共享的单一读索引读取入口。 */
    public String readCurrentReadIndex() {
        return currentReadIndex();
    }

    public String currentWriteIndex() {
        Set<String> indexes = currentWriteIndexes();
        return indexes.isEmpty() ? null : indexes.iterator().next();
    }

    public Set<String> currentWriteIndexes() {
        initializeIfNeeded();
        return aliasMembers(ProductSearchPort.WRITE_ALIAS, "读取写别名失败");
    }

    public Set<String> readAllAliasMembers() {
        Set<String> all = new LinkedHashSet<>(currentReadIndexes());
        all.addAll(currentWriteIndexes());
        return Set.copyOf(all);
    }

    public String newRebuildIndexName() {
        return REBUILD_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    public String createRebuildIndex() {
        return createRebuildIndex(newRebuildIndexName());
    }

    public String createRebuildIndex(String index) {
        if (index == null || index.isBlank()) {
            throw new IllegalArgumentException("目标索引不能为空");
        }
        try {
            client.indices().create(create -> create.index(index)
                    .settings(settings -> settings.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(ElasticsearchProductSearch::mapping));
            return index;
        } catch (IOException | RuntimeException failure) {
            throw new SearchUnavailableException("创建重建索引失败", failure);
        }
    }

    /** 在读侧门禁之外执行别名切换；调用方负责提供一致性快照和并发协调。 */
    public AliasTransition replaceAliasesWithSingleTarget(String targetIndex, Set<String> live) {
        if (targetIndex == null || targetIndex.isBlank()) {
            throw new IllegalArgumentException("目标索引不能为空");
        }
        Objects.requireNonNull(live, "当前别名成员不能为空");
        try {
            if (!client.indices().exists(exists -> exists.index(targetIndex)).value()) {
                throw new SearchUnavailableException("目标索引不存在，别名切换可重试");
            }
            client.indices().updateAliases(update -> {
                for (String index : live) {
                    update.actions(action -> action.remove(remove -> remove.index(index)
                            .alias(ProductSearchPort.READ_ALIAS)));
                    update.actions(action -> action.remove(remove -> remove.index(index)
                            .alias(ProductSearchPort.WRITE_ALIAS)));
                }
                update.actions(action -> action.add(add -> add.index(targetIndex)
                        .alias(ProductSearchPort.READ_ALIAS)));
                update.actions(action -> action.add(add -> add.index(targetIndex)
                        .alias(ProductSearchPort.WRITE_ALIAS).isWriteIndex(true)));
                return update;
            });
            initialized = true;
            return new AliasTransition(live);
        } catch (IOException failure) {
            throw new SearchUnavailableException("执行搜索别名切换失败", failure);
        } catch (SearchUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SearchUnavailableException("执行搜索别名切换失败", failure);
        }
    }

    public void deleteIndex(String index) {
        if (index == null || index.isBlank()) {
            throw new IllegalArgumentException("待删除索引不能为空");
        }
        try {
            client.indices().delete(delete -> delete.index(index));
        } catch (ElasticsearchException missing) {
            if (missing.status() != 404) {
                throw new SearchUnavailableException("搜索索引删除失败", missing);
            }
        } catch (IOException failure) {
            throw new SearchUnavailableException("搜索索引删除失败", failure);
        }
    }

    public void ensureInitializedForAliasRead() {
        initializeIfNeeded();
    }

    /**
     * 兼容重建服务在持有读库门禁连接时的调用形态；搜索初始化本身不访问该连接。
     */
    public void ensureInitializedForAliasRead(Connection connection) {
        Objects.requireNonNull(connection, "连接不能为空");
        initializeIfNeeded();
    }

    public record AliasTransition(Set<String> previousIndexes) {
        public AliasTransition {
            previousIndexes = Set.copyOf(Objects.requireNonNull(previousIndexes, "旧别名成员不能为空"));
        }
    }

    public static class SearchUnavailableException extends ProductSearchPort.SearchUnavailableException {
        public SearchUnavailableException(String message) {
            super(message);
        }

        public SearchUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void initializeIfNeeded() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            try {
                boolean exists = client.indices().exists(index -> index.index(INITIAL_INDEX)).value();
                if (!exists) {
                    client.indices().create(create -> create.index(INITIAL_INDEX)
                            .settings(settings -> settings.numberOfShards("1").numberOfReplicas("0"))
                            .mappings(ElasticsearchProductSearch::mapping));
                }
                ensureAlias(INITIAL_INDEX, ProductSearchPort.READ_ALIAS, false);
                ensureAlias(INITIAL_INDEX, ProductSearchPort.WRITE_ALIAS, true);
                initialized = true;
            } catch (IOException failure) {
                throw new SearchUnavailableException("初始化搜索索引失败", failure);
            } catch (ElasticsearchException failure) {
                throw new SearchUnavailableException("初始化搜索索引失败", failure);
            } catch (RuntimeException failure) {
                throw new SearchUnavailableException("初始化搜索索引失败", failure);
            }
        }
    }

    private void ensureAlias(String index, String alias, boolean write) throws IOException {
        try {
            client.indices().getAlias(get -> get.name(alias));
        } catch (ElasticsearchException missing) {
            if (missing.status() != 404) {
                throw missing;
            }
            client.indices().updateAliases(update -> update.actions(action -> action.add(add -> add
                    .index(index).alias(alias).isWriteIndex(write))));
        }
    }

    private Set<String> aliasMembers(String alias, String message) {
        try {
            return Set.copyOf(client.indices().getAlias(get -> get.name(alias)).aliases().keySet());
        } catch (IOException | RuntimeException failure) {
            throw new SearchUnavailableException(message, failure);
        }
    }

    private static Query query(SearchRequest request) {
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(query -> query.term(term -> term.field("status").value("ON_SALE"))));
        filters.add(Query.of(query -> query.range(range -> range.untyped(untyped -> untyped
                .field("availableQuantity").gt(JsonData.of(0))))));
        if (request.category() != null) {
            filters.add(Query.of(query -> query.term(term -> term.field("category").value(request.category()))));
        }
        if (request.minPriceFen() != null) {
            filters.add(Query.of(query -> query.range(range -> range.untyped(untyped -> untyped
                    .field("unitPriceFen").gte(JsonData.of(request.minPriceFen()))))));
        }
        if (request.maxPriceFen() != null) {
            filters.add(Query.of(query -> query.range(range -> range.untyped(untyped -> untyped
                    .field("unitPriceFen").lte(JsonData.of(request.maxPriceFen()))))));
        }
        if (request.keyword().isEmpty()) {
            return Query.of(query -> query.bool(bool -> bool.filter(filters)));
        }
        return Query.of(query -> query.bool(bool -> bool.filter(filters)
                .must(must -> must.multiMatch(match -> match.query(request.keyword())
                        .fields("title", "description").analyzer("smartcn")))));
    }

    private static TypeMapping.Builder mapping(TypeMapping.Builder mapping) {
        return mapping
                .properties("listingId", property -> property.keyword(keyword -> keyword))
                .properties("title", property -> property.text(text -> text
                        .analyzer("smartcn").searchAnalyzer("smartcn")))
                .properties("description", property -> property.text(text -> text
                        .analyzer("smartcn").searchAnalyzer("smartcn")))
                .properties("category", property -> property.keyword(keyword -> keyword))
                .properties("unitPriceFen", property -> property.long_(number -> number))
                .properties("availableQuantity", property -> property.integer(number -> number))
                .properties("status", property -> property.keyword(keyword -> keyword))
                .properties("aggregateVersion", property -> property.long_(number -> number));
    }

    private static Map<String, Object> toMap(ProductDocument document) {
        Map<String, Object> source = new HashMap<>();
        source.put("listingId", document.listingId());
        source.put("title", document.title());
        source.put("description", document.description());
        source.put("category", document.category());
        source.put("unitPriceFen", document.unitPriceFen());
        source.put("availableQuantity", document.availableQuantity());
        source.put("status", document.status());
        source.put("aggregateVersion", document.aggregateVersion());
        return source;
    }

    private static List<FieldValue> searchAfter(ProductSearchPort.SearchCursor cursor) {
        return List.of(FieldValue.of(cursor.score()), FieldValue.of(cursor.listingId()));
    }

    private static String fingerprint(ProductSearchPort.SearchRequest request) {
        String value = request.keyword() + "\u0000" + Objects.toString(request.category(), "") + "\u0000"
                + Objects.toString(request.minPriceFen(), "") + "\u0000"
                + Objects.toString(request.maxPriceFen(), "");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static SearchItem fromMap(Map source) {
        if (source == null) {
            throw new SearchUnavailableException("搜索文档为空");
        }
        try {
            return new SearchItem(
                    required(source, "listingId"),
                    required(source, "title"),
                    required(source, "description"),
                    required(source, "category"),
                    number(source, "unitPriceFen").longValue(),
                    number(source, "availableQuantity").intValue(),
                    required(source, "status"),
                    number(source, "aggregateVersion").longValue());
        } catch (RuntimeException failure) {
            if (failure instanceof SearchUnavailableException unavailable) {
                throw unavailable;
            }
            throw new SearchUnavailableException("搜索文档字段无效", failure);
        }
    }

    private static String required(Map<?, ?> source, String field) {
        Object value = source.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new SearchUnavailableException("搜索文档字段缺失");
        }
        return String.valueOf(value);
    }

    private static Number number(Map<?, ?> source, String field) {
        Object value = source.get(field);
        if (!(value instanceof Number number)) {
            throw new SearchUnavailableException("搜索文档字段类型无效");
        }
        return number;
    }

    private void closePit(String pit) {
        if (pit == null || pit.isBlank()) {
            return;
        }
        try {
            client.closePointInTime(close -> close.id(pit));
        } catch (IOException | RuntimeException ignored) {
            // PIT 关闭失败不应覆盖原始搜索结果或错误。
        }
    }

    private static boolean isVersionConflict(Throwable failure) {
        if (failure instanceof ElasticsearchException elasticsearchException
                && elasticsearchException.status() == 409) {
            return true;
        }
        String message = failure.getMessage();
        return message != null && (message.contains("version_conflict") || message.contains("409 Conflict"));
    }
}
