package com.example.campusmarket.catalog.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Elasticsearch 商品索引实现，所有写入均通过 external_gte 版本保护。 */
@Component
public class ElasticsearchProductSearch implements ProductSearchPort {
    private static final String INITIAL_INDEX = "campus-listing-000001";
    private final ElasticsearchClient client;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private volatile boolean initialized;
    private final String cleanupOwner = "search-cleanup-" + UUID.randomUUID();
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchProductSearch.class);

    @Autowired
    public ElasticsearchProductSearch(ElasticsearchClient client, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.client = Objects.requireNonNull(client, "Elasticsearch 客户端不能为空");
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    @Override
    public void index(ProductDocument document) {
        initializeIfNeeded();
        Objects.requireNonNull(document, "商品文档不能为空");
        try {
            String writeIndex = currentWriteIndex();
            client.index(i -> i.index(writeIndex == null ? WRITE_ALIAS : writeIndex).id(document.listingId())
                .version(document.aggregateVersion()).versionType(VersionType.ExternalGte)
                .document(toMap(document)));
        } catch (IOException e) {
            if (isVersionConflict(e)) return;
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
            String writeIndex = currentWriteIndex();
            client.index(i -> i.index(writeIndex == null ? WRITE_ALIAS : writeIndex).id(listingId).version(aggregateVersion)
                .versionType(VersionType.ExternalGte).document(toMap(ProductDocument.tombstone(listingId, aggregateVersion))));
        } catch (IOException e) {
            if (isVersionConflict(e)) return;
            throw new SearchUnavailableException("商品索引删除失败", e);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (!isVersionConflict(e)) throw e;
        }
    }

    @Override
    public SearchPage search(SearchRequest request) {
        initializeIfNeeded();
        Objects.requireNonNull(request, "搜索请求不能为空");
        String pit = null;
        try {
            Query query = query(request);
            ProductSearchPort.SearchCursor cursor = request.searchAfter() == null ? null : ProductSearchPort.decodeCursor(request.searchAfter());
            String fingerprint = fingerprint(request);
            if (cursor != null && (cursor.pitId().isBlank() || !fingerprint.equals(cursor.fingerprint()))) {
                throw new IllegalArgumentException("搜索游标与查询条件不匹配");
            }
            pit = cursor == null ? client.openPointInTime(o -> o.index(READ_ALIAS).keepAlive(co.elastic.clients.elasticsearch._types.Time.of(t -> t.time("1m")))).id() : cursor.pitId();
            String requestPit = pit;
            SearchResponse<Map> response = client.search(s -> {
                s.pit(p -> p.id(requestPit).keepAlive(co.elastic.clients.elasticsearch._types.Time.of(t -> t.time("1m")))).query(query).size(request.size() + 1).trackTotalHits(t -> t.enabled(true))
                    // Score is ordered first; listing ID is a deterministic tie-breaker.
                    .sort(sort -> sort.score(sc -> sc.order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                    .sort(sort -> sort.field(f -> f.field("listingId")
                        .order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)));
                if (cursor != null) s.searchAfter(searchAfter(cursor));
                return s;
            }, Map.class);
            String responsePit = response.pitId();
            String currentPit = responsePit == null || responsePit.isBlank() ? pit : responsePit;
            // Elasticsearch may rotate the PIT id on every page. Keep the
            // newest id so an exception while decoding hits can still close
            // the controllable PIT rather than leaking the original one.
            pit = currentPit;
            List<SearchItem> items = new ArrayList<>();
            String next = null;
            List<Hit<Map>> hits = response.hits().hits();
            boolean hasMore = hits.size() > request.size();
            for (int i = 0; i < Math.min(request.size(), hits.size()); i++) {
                Hit<Map> hit = hits.get(i);
                items.add(fromMap(hit.source()));
                if (hit.sort().size() >= 2) next = ProductSearchPort.encodeCursor(currentPit, fingerprint, hit.sort().get(0).doubleValue(), hit.sort().get(1).stringValue());
            }
            long total = response.hits().total() == null ? items.size() : response.hits().total().value();
            if (!hasMore) {
                next = null;
                closePit(currentPit);
            }
            return new SearchPage(items, total, next);
        } catch (IOException | RuntimeException e) {
            if (pit != null) {
                closePit(pit);
            }
            if (e instanceof SearchUnavailableException unavailable) throw unavailable;
            if (e instanceof IllegalArgumentException invalid) throw invalid;
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
        return createRebuildIndex(newRebuildIndexName());
    }

    public String newRebuildIndexName() {
        return "campus-listing-rebuild-" + UUID.randomUUID().toString().replace("-", "");
    }

    public String createRebuildIndex(String index) {
        initializeIfNeeded();
        if (index == null || index.isBlank()) throw new IllegalArgumentException("目标索引不能为空");
        try {
            client.indices().create(c -> c.index(index).settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                .mappings(ElasticsearchProductSearch::mapping));
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
            if (isVersionConflict(e)) return;
            throw new SearchUnavailableException("重建索引写入失败", e);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (!isVersionConflict(e)) throw e;
        }
    }

    public void tombstoneInto(String index, String listingId, long aggregateVersion) {
        try {
            client.index(i -> i.index(index).id(listingId).version(aggregateVersion)
                .versionType(VersionType.ExternalGte).document(toMap(ProductDocument.tombstone(listingId, aggregateVersion))));
        } catch (IOException e) {
            if (isVersionConflict(e)) return;
            throw new SearchUnavailableException("重建索引删除失败", e);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (!isVersionConflict(e)) throw e;
        }
    }

    public void scheduleCleanup(String index) {
        if (index == null || index.isBlank() || index.startsWith("campus-listing-000001")) return;
        jdbc.update("INSERT INTO search_index_cleanup_task(id,index_name,status,attempt_count,available_at,created_at) VALUES (?,?, 'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE status=IF(status='DONE',status,'NEW'),available_at=IF(status='DONE',available_at,CURRENT_TIMESTAMP(6))", UUID.randomUUID().toString(), index);
    }

    public void recordRebuildIntent(String target, String previous) {
        if (target == null || target.isBlank()) throw new IllegalArgumentException("目标索引不能为空");
        jdbc.update("INSERT INTO search_rebuild_intent(id,target_index,previous_index,phase,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE previous_index=VALUES(previous_index),updated_at=CURRENT_TIMESTAMP(6)", UUID.randomUUID().toString(), target, previous, "CREATED");
    }

    public void markRebuildIntentSwitched(String target) {
        jdbc.update("UPDATE search_rebuild_intent SET phase='SWITCHED',updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=?", target);
    }

    public void updateRebuildIntentPrevious(String target, String previous) {
        jdbc.update("UPDATE search_rebuild_intent SET previous_index=?,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='CREATED'", previous, target);
    }

    public void markRebuildIntentBuilding(String target) {
        jdbc.update("UPDATE search_rebuild_intent SET phase='BUILDING',updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='CREATED'", target);
    }

    /** Register an index while it may still be live or being populated. */
    public void registerRebuildTarget(String index) {
        if (index == null || index.isBlank() || index.startsWith("campus-listing-000001")) return;
        jdbc.update("INSERT INTO search_index_cleanup_task(id,index_name,status,owner_id,claim_token,lease_until,attempt_count,available_at,created_at) VALUES (?,?, 'BUILDING',?,?,TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE status='BUILDING',owner_id=VALUES(owner_id),claim_token=VALUES(claim_token),lease_until=VALUES(lease_until)", UUID.randomUUID().toString(), index, cleanupOwner, UUID.randomUUID().toString());
    }

    public boolean renewRebuildTarget(String index) {
        if (index == null || index.isBlank()) return false;
        return jdbc.update("UPDATE search_index_cleanup_task SET lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE index_name=? AND status='BUILDING' AND owner_id=? AND lease_until > CURRENT_TIMESTAMP(6)", index, cleanupOwner) == 1;
    }

    /** Make a failed/unreferenced index eligible for the cleanup worker. */
    public void armCleanup(String index) {
        if (index == null || index.isBlank()) return;
        jdbc.update("UPDATE search_index_cleanup_task SET status='NEW',available_at=CURRENT_TIMESTAMP(6),owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE index_name=? AND status='BUILDING'", index);
    }

    /** Mark a protected BUILDING target as live before cleanup workers can see it. */
    public void cancelCleanup(String index) {
        if (index == null || index.isBlank()) return;
        jdbc.update("UPDATE search_index_cleanup_task SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL,last_error=NULL,failure_class=NULL WHERE index_name=?", index);
    }

    @Transactional
    public void cleanupPending() {
        reconcileRebuildIntents();
        String mode = jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1", String.class);
        if (!"OPEN".equals(mode)) return;
        jdbc.update("UPDATE search_index_cleanup_task SET status='FAILED',owner_id=NULL,claim_token=NULL,lease_until=NULL,last_error='cleanup attempt limit exhausted',failure_class='TRANSIENT' WHERE status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP(6) AND attempt_count >= 3");
        List<CleanupClaim> claims = jdbc.query("SELECT id,index_name,attempt_count,status FROM search_index_cleanup_task WHERE (status='NEW' AND available_at <= CURRENT_TIMESTAMP(6)) OR (status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP(6) AND attempt_count < 3) OR (status='BUILDING' AND lease_until <= CURRENT_TIMESTAMP(6)) ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED", (rs, rowNum) -> new CleanupClaim(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
        for (CleanupClaim claim : claims) {
            if ("BUILDING".equals(claim.status())) {
                String liveRead = currentReadIndex();
                String liveWrite = currentWriteIndex();
                if (claim.indexName().equals(liveRead) || claim.indexName().equals(liveWrite)) {
                    jdbc.update("UPDATE search_index_cleanup_task SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE id=? AND status='BUILDING' AND lease_until <= CURRENT_TIMESTAMP(6)", claim.id());
                    continue;
                }
            }
            String token = UUID.randomUUID().toString();
            if (jdbc.update("UPDATE search_index_cleanup_task SET status='RUNNING',owner_id=?,claim_token=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),attempt_count=attempt_count+1 WHERE id=? AND ((status='NEW' AND available_at <= CURRENT_TIMESTAMP(6)) OR (status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP(6) AND attempt_count < 3) OR (status='BUILDING' AND lease_until <= CURRENT_TIMESTAMP(6)))", cleanupOwner, token, claim.id()) != 1) continue;
            try {
                if (claim.indexName().equals(currentReadIndex()) || claim.indexName().equals(currentWriteIndex())) {
                    jdbc.update("UPDATE search_index_cleanup_task SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=?", claim.id(), cleanupOwner, token);
                    continue;
                }
                try { client.indices().delete(d -> d.index(claim.indexName())); }
                catch (co.elastic.clients.elasticsearch._types.ElasticsearchException missing) { if (missing.status() != 404) throw missing; }
                jdbc.update("UPDATE search_index_cleanup_task SET status='DONE',owner_id=NULL,claim_token=NULL,lease_until=NULL,last_error=NULL,failure_class=NULL WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=? AND lease_until > CURRENT_TIMESTAMP(6)", claim.id(), cleanupOwner, token);
            } catch (RuntimeException | IOException failure) {
                String kind = permanentFailure(failure) ? "PERMANENT" : "TRANSIENT";
                String message = failure.getMessage() == null ? kind : failure.getMessage().substring(0, Math.min(500, failure.getMessage().length()));
                String status = claim.attemptCount() + 1 >= 3 ? "FAILED" : "NEW";
                jdbc.update("UPDATE search_index_cleanup_task SET status=?,owner_id=NULL,claim_token=NULL,lease_until=NULL,available_at=TIMESTAMPADD(SECOND,10,CURRENT_TIMESTAMP(6)),last_error=?,failure_class=? WHERE id=? AND status='RUNNING' AND owner_id=? AND claim_token=? AND lease_until > CURRENT_TIMESTAMP(6)", status, message, kind, claim.id(), cleanupOwner, token);
                if ("FAILED".equals(status)) LOGGER.warn("搜索索引清理进入 FAILED，failureClass={}", kind);
            }
        }
    }

    private record CleanupClaim(String id, String indexName, int attemptCount, String status) { }

    private void reconcileRebuildIntents() {
        List<RebuildIntent> intents = jdbc.query("SELECT target_index,previous_index,phase FROM search_rebuild_intent WHERE phase <> 'RECONCILED' ORDER BY created_at LIMIT 20", (rs, rowNum) -> new RebuildIntent(rs.getString(1), rs.getString(2), rs.getString(3)));
        String liveRead = currentReadIndex();
        String liveWrite = currentWriteIndex();
        for (RebuildIntent intent : intents) {
            if (intent.target().equals(liveRead) || intent.target().equals(liveWrite)) {
                cancelCleanup(intent.target());
                if (intent.previous() != null && !intent.previous().equals(liveRead) && !intent.previous().equals(liveWrite)) scheduleCleanup(intent.previous());
                jdbc.update("UPDATE search_rebuild_intent SET phase='RECONCILED',updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=?", intent.target());
            } else if ("BUILDING".equals(intent.phase()) || "SWITCHED".equals(intent.phase())) {
                String taskStatus = jdbc.query("SELECT status FROM search_index_cleanup_task WHERE index_name=?", rs -> rs.next() ? rs.getString(1) : null, intent.target());
                if (taskStatus == null || "BUILDING".equals(taskStatus)) {
                    if (taskStatus == null) scheduleCleanup(intent.target());
                    else continue;
                } else {
                    armCleanup(intent.target());
                }
                jdbc.update("UPDATE search_rebuild_intent SET phase='RECONCILED',updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=?", intent.target());
            } else if ("CREATED".equals(intent.phase())) {
                scheduleCleanup(intent.target());
                jdbc.update("UPDATE search_rebuild_intent SET phase='RECONCILED',updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=?", intent.target());
            }
        }
    }

    private record RebuildIntent(String target, String previous, String phase) { }

    public String currentWriteIndex() {
        initializeIfNeeded();
        try {
            var response = client.indices().getAlias(g -> g.name(WRITE_ALIAS));
            return response.result().keySet().stream().findFirst().orElse(null);
        } catch (IOException e) {
            throw new SearchUnavailableException("读取写别名失败", e);
        }
    }

    private static boolean permanentFailure(Throwable failure) {
        if (failure instanceof co.elastic.clients.elasticsearch._types.ElasticsearchException elastic) {
            return elastic.status() == 400 || elastic.status() == 403 || elastic.status() == 409;
        }
        return false;
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
            .mappings(ElasticsearchProductSearch::mapping));
    }

    private static TypeMapping.Builder mapping(TypeMapping.Builder m) {
        return m.properties("listingId", p -> p.keyword(k -> k))
            .properties("title", p -> p.text(t -> t.analyzer("smartcn").searchAnalyzer("smartcn")))
            .properties("description", p -> p.text(t -> t.analyzer("smartcn").searchAnalyzer("smartcn")))
            .properties("category", p -> p.keyword(k -> k))
            .properties("unitPriceFen", p -> p.long_(l -> l))
            .properties("availableQuantity", p -> p.integer(i -> i))
            .properties("status", p -> p.keyword(k -> k))
            .properties("aggregateVersion", p -> p.long_(l -> l));
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

    private static List<co.elastic.clients.elasticsearch._types.FieldValue> searchAfter(ProductSearchPort.SearchCursor cursor) {
        return List.of(co.elastic.clients.elasticsearch._types.FieldValue.of(cursor.score()),
            co.elastic.clients.elasticsearch._types.FieldValue.of(cursor.listingId()));
    }

    private void closePit(String pit) {
        try { client.closePointInTime(c -> c.id(pit)); } catch (IOException ignored) { }
    }

    private static String fingerprint(ProductSearchPort.SearchRequest request) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            (request.keyword() + "\u0000" + java.util.Objects.toString(request.category(), "") + "\u0000"
                + java.util.Objects.toString(request.minPriceFen(), "") + "\u0000" + java.util.Objects.toString(request.maxPriceFen(), ""))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean isVersionConflict(co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
        return e.status() == 409 || (e.getMessage() != null && e.getMessage().contains("version_conflict"));
    }

    private static boolean isVersionConflict(Throwable e) {
        String message = e.getMessage();
        return message != null && (message.contains("version_conflict") || message.contains("409 Conflict"));
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
