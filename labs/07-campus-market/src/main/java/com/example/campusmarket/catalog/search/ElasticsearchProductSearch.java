package com.example.campusmarket.catalog.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;

/** Elasticsearch 商品索引实现，所有写入均通过 external_gte 版本保护。 */
@Component
public class ElasticsearchProductSearch implements ProductSearchPort {
    private static final String INITIAL_INDEX = "campus-listing-000001";
    private final ElasticsearchClient client;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final SearchAliasCoordinator coordinator;
    private final SearchIndexCleanupRepository cleanupRepository;
    private final MeterRegistry metrics;
    private final Consumer<Set<String>> aliasMembersReadHook;
    private volatile boolean initialized;
    private final String cleanupOwner = "search-cleanup-" + UUID.randomUUID();

    public ElasticsearchProductSearch(ElasticsearchClient client, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this(client, jdbc, ignored -> { }, new SimpleMeterRegistry());
    }

    @Autowired
    public ElasticsearchProductSearch(ElasticsearchClient client, org.springframework.jdbc.core.JdbcTemplate jdbc,
                                      MeterRegistry metrics) {
        this(client, jdbc, ignored -> { }, metrics);
    }

    /** 跨实例互斥锁持有期间、读取 live 别名后且 ES 别名请求前触发的测试接缝。 */
    public ElasticsearchProductSearch(ElasticsearchClient client, org.springframework.jdbc.core.JdbcTemplate jdbc,
                                      Consumer<Set<String>> aliasMembersReadHook) {
        this(client, jdbc, aliasMembersReadHook, new SimpleMeterRegistry());
    }

    ElasticsearchProductSearch(ElasticsearchClient client, org.springframework.jdbc.core.JdbcTemplate jdbc,
                               Consumer<Set<String>> aliasMembersReadHook, MeterRegistry metrics) {
        this.client = Objects.requireNonNull(client, "Elasticsearch 客户端不能为空");
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "JDBC数据源不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标注册表不能为空");
        this.coordinator = new SearchAliasCoordinator(this.dataSource, this.metrics);
        this.cleanupRepository = new SearchIndexCleanupRepository(this.jdbc);
        this.aliasMembersReadHook = Objects.requireNonNull(aliasMembersReadHook, "别名读取 hook 不能为空");
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
                    // 先按得分排序，商品 ID 作为确定性的平局决胜字段。
                    .sort(sort -> sort.score(sc -> sc.order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                    .sort(sort -> sort.field(f -> f.field("listingId")
                        .order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)));
                if (cursor != null) s.searchAfter(searchAfter(cursor));
                return s;
            }, Map.class);
            String responsePit = response.pitId();
            String currentPit = responsePit == null || responsePit.isBlank() ? pit : responsePit;
            // Elasticsearch 可能在每页轮换 PIT ID。保留最新 ID，确保解码命中结果异常时
            // 仍能关闭可控的 PIT，而不会泄露原始 PIT。
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

    /** 读取具体 read/write 别名成员的完整并集。 */
    Set<String> readAllAliasMembers() {
        try {
            Set<String> live = new LinkedHashSet<>();
            live.addAll(aliasMembers(READ_ALIAS));
            live.addAll(aliasMembers(WRITE_ALIAS));
            return Set.copyOf(live);
        } catch (IOException e) {
            throw new SearchUnavailableException("读取搜索别名成员失败", e);
        }
    }

    void ensureInitializedForAliasRead() {
        initializeIfNeeded();
    }

    void ensureInitializedForAliasRead(Connection connection) {
        Objects.requireNonNull(connection, "连接不能为空");
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            ensureInitialIndex(connection);
            initialized = true;
        }
    }

    /**
     * 将两个别名替换为单一 target。该包级操作只能由持有协调锁的重建/协调临界区调用。
     */
    AliasTransition replaceAliasesWithSingleTarget(String targetIndex, Set<String> live) {
        if (targetIndex == null || targetIndex.isBlank()) throw new IllegalArgumentException("目标索引不能为空");
        Objects.requireNonNull(live, "当前别名成员不能为空");
        try {
            if (!client.indices().exists(e -> e.index(targetIndex)).value()) {
                throw new SearchUnavailableException("目标索引不存在，别名切换可重试");
            }
            aliasMembersReadHook.accept(Set.copyOf(live));
            client.indices().updateAliases(a -> {
                for (String index : live) {
                    a.actions(action -> action.remove(r -> r.index(index).alias(READ_ALIAS)));
                    a.actions(action -> action.remove(r -> r.index(index).alias(WRITE_ALIAS)));
                }
                a.actions(action -> action.add(x -> x.index(targetIndex).alias(READ_ALIAS)));
                a.actions(action -> action.add(x -> x.index(targetIndex).alias(WRITE_ALIAS).isWriteIndex(true)));
                return a;
            });
            metrics.counter("search.alias.mutation.success").increment();
            return new AliasTransition(live);
        } catch (IOException e) {
            metrics.counter("search.alias.mutation.failure").increment();
            throw new SearchUnavailableException("校验或执行搜索别名切换失败", e);
        } catch (RuntimeException failure) {
            metrics.counter("search.alias.mutation.failure").increment();
            throw failure;
        }
    }

    /**
     * 在外部别名请求前保护所有原 live 成员。行 owner/token 标识切换意图，
     * 这样进程在 ES 成功后停止时，协调仍可恢复暂存集合。
     */
    void stageCleanup(Connection connection, Set<String> live, String target, String owner, String token) {
        cleanupRepository.stage(connection, live, target, owner, token);
    }

    /** 只有别名请求完成后才将暂存成员置为可清理。 */
    void armStagedCleanup(Connection connection, Set<String> staged, Set<String> stillLive, String owner, String token) {
        cleanupRepository.arm(connection, staged, stillLive, owner, token);
    }

    /** 恢复被中断切换意图暂存的行。 */
    void recoverStagedCleanup(Connection connection, Set<String> stillLive, String owner, String token) {
        cleanupRepository.recover(connection, stillLive, owner, token);
    }

    void cancelCleanup(Connection connection, String index) {
        cleanupRepository.cancel(connection, index);
    }

    void assertSwitchingIntent(Connection connection, String target, String owner, String token, long generation) {
        Objects.requireNonNull(connection, "连接不能为空");
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM search_rebuild_intent
            WHERE target_index=? AND phase='SWITCHING' AND owner_id=? AND claim_token=?
              AND generation=? AND lease_until > CURRENT_TIMESTAMP(6)
            """)) {
            statement.setString(1, target);
            statement.setString(2, owner);
            statement.setString(3, token);
            statement.setLong(4, generation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SearchGateRepository.SearchGateClosedException("重建切换意图已失效");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("校验重建切换意图失败", failure);
        }
    }

    private <T> T withAliasCoordinator(Function<Connection, T> operation) {
        return coordinator.execute(java.time.Duration.ofSeconds(30), "initialize-alias", cleanupOwner, operation::apply);
    }

    private Set<String> aliasMembers(String alias) throws IOException {
        var response = client.indices().getAlias(g -> g.name(alias));
        return response.result().keySet();
    }

    record AliasTransition(Set<String> previousIndexes) {
        public AliasTransition {
            previousIndexes = Set.copyOf(Objects.requireNonNull(previousIndexes, "旧别名成员不能为空"));
        }
    }

    public String currentReadIndex() {
        Set<String> members = currentReadIndexes();
        return members.isEmpty() ? null : members.iterator().next();
    }

    public Set<String> currentReadIndexes() {
        initializeIfNeeded();
        return readCurrentReadIndexes();
    }

    Set<String> readCurrentReadIndexes() {
        try {
            var response = client.indices().getAlias(g -> g.name(READ_ALIAS));
            return Set.copyOf(response.result().keySet());
        } catch (IOException e) {
            throw new SearchUnavailableException("读取搜索别名失败", e);
        }
    }

    String readCurrentReadIndex() {
        Set<String> members = readCurrentReadIndexes();
        return members.isEmpty() ? null : members.iterator().next();
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
        cleanupRepository.schedule(index);
    }

    public void recordRebuildIntent(String target, String previous) {
        if (target == null || target.isBlank()) throw new IllegalArgumentException("目标索引不能为空");
        jdbc.update("INSERT INTO search_rebuild_intent(id,target_index,previous_index,phase,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE previous_index=VALUES(previous_index),updated_at=CURRENT_TIMESTAMP(6)", UUID.randomUUID().toString(), target, previous, "CREATED");
    }

    void recordRebuildIntent(Connection connection, String target, String previous) {
        Objects.requireNonNull(connection, "连接不能为空");
        if (target == null || target.isBlank()) throw new IllegalArgumentException("目标索引不能为空");
        update(connection, "INSERT INTO search_rebuild_intent(id,target_index,previous_index,phase,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE previous_index=VALUES(previous_index),updated_at=CURRENT_TIMESTAMP(6)", UUID.randomUUID().toString(), target, previous, "CREATED");
    }

    public void markRebuildIntentSwitched(String target) {
        jdbc.update("UPDATE search_rebuild_intent SET phase='SWITCHED',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=?", target);
    }

    /** 在短小且持久的数据库操作中领取最终别名切换。 */
    public boolean claimRebuildIntentSwitch(String target, String owner, String token, long generation) {
        if (target == null || target.isBlank() || owner == null || owner.isBlank()
            || token == null || token.isBlank() || generation <= 0) throw new IllegalArgumentException("重建切换领取参数无效");
        return jdbc.update("UPDATE search_rebuild_intent SET phase='SWITCHING',owner_id=?,claim_token=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),generation=?,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='BUILDING'", owner, token, generation, target) == 1;
    }

    public boolean markRebuildIntentSwitched(String target, String owner, String token) {
        return jdbc.update("UPDATE search_rebuild_intent SET phase='SWITCHED',owner_id=NULL,claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='SWITCHING' AND owner_id=? AND claim_token=? AND lease_until > CURRENT_TIMESTAMP(6)", target, owner, token) == 1;
    }

    public void updateRebuildIntentPrevious(String target, String previous) {
        jdbc.update("UPDATE search_rebuild_intent SET previous_index=?,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='CREATED'", previous, target);
    }

    void updateRebuildIntentPrevious(Connection connection, String target, String previous) {
        Objects.requireNonNull(connection, "连接不能为空");
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE search_rebuild_intent SET previous_index=?,updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='CREATED'")) {
            statement.setString(1, previous);
            statement.setString(2, target);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("更新重建意图旧索引失败", failure);
        }
    }

    public void markRebuildIntentBuilding(String target) {
        jdbc.update("UPDATE search_rebuild_intent SET phase='BUILDING',updated_at=CURRENT_TIMESTAMP(6) WHERE target_index=? AND phase='CREATED'", target);
    }

    /** 在索引可能仍 live 或仍在填充时登记索引。 */
    public void registerRebuildTarget(String index) {
        cleanupRepository.registerBuilding(index, cleanupOwner, UUID.randomUUID().toString());
    }

    public boolean renewRebuildTarget(String index) {
        return cleanupRepository.renewBuilding(index, cleanupOwner);
    }

    /** 让失败或无引用索引进入清理 worker 的候选范围。 */
    public void armCleanup(String index) {
        cleanupRepository.arm(index);
    }

    /** 在清理 worker 可见前，将受保护的 BUILDING target 标记为 live。 */
    public void cancelCleanup(String index) {
        cleanupRepository.cancel(index);
    }

    private static void update(Connection connection, String sql, Object... values) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("更新索引清理状态失败", failure);
        }
    }

    SearchIndexCleanupRepository cleanupRepository() {
        return cleanupRepository;
    }

    /** 作为 ES 原语删除索引；所有权和 fencing 由 worker 负责。 */
    void deleteIndex(String index) {
        if (index == null || index.isBlank()) throw new IllegalArgumentException("待删除索引不能为空");
        try {
            client.indices().delete(d -> d.index(index));
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException missing) {
            if (missing.status() != 404) throw missing;
        } catch (IOException failure) {
            throw new SearchUnavailableException("搜索索引删除失败", failure);
        }
    }

    public String currentWriteIndex() {
        Set<String> members = currentWriteIndexes();
        return members.isEmpty() ? null : members.iterator().next();
    }

    public Set<String> currentWriteIndexes() {
        initializeIfNeeded();
        try {
            var response = client.indices().getAlias(g -> g.name(WRITE_ALIAS));
            return Set.copyOf(response.result().keySet());
        } catch (IOException e) {
            throw new SearchUnavailableException("读取写别名失败", e);
        }
    }


    private void ensureInitialIndex() {
        withAliasCoordinator(connection -> {
            ensureInitialIndex(connection);
            return null;
        });
    }

    private void ensureInitialIndex(Connection connection) {
        Objects.requireNonNull(connection, "连接不能为空");
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
