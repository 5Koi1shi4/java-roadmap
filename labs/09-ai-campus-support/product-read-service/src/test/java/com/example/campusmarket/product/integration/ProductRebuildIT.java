package com.example.campusmarket.product.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.example.campusmarket.product.infrastructure.ProductIndexCleanupRepository;
import com.example.campusmarket.product.infrastructure.ProductRebuildGateRepository;
import com.example.campusmarket.product.search.ElasticsearchProductSearch;
import com.example.campusmarket.product.search.ProductIndexCleanupWorker;
import com.example.campusmarket.product.search.ProductSearchPort;
import com.example.campusmarket.product.search.ProductSearchRebuildService;
import com.example.campusmarket.product.search.ProductSearchRebuildRecoveryService;
import com.example.campusmarket.testsupport.SplitDatabaseContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.sql.Timestamp;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 product_read_db + SmartCN ES 验证一致性快照、门禁 fencing 与安全清理。 */
@Testcontainers
class ProductRebuildIT {
    private static final String ES_IMAGE = "campus-market/product-read-elasticsearch:9.4.5-smartcn";
    private static final ImageFromDockerfile SMART_CN_IMAGE = new ImageFromDockerfile(ES_IMAGE, true)
            .withDockerfile(Path.of("../docker/elasticsearch/Dockerfile"));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = elasticsearchContainer();

    private static JdbcTemplate jdbc;
    private static DataSource dataSource;
    private static ElasticsearchTransport transport;
    private static ElasticsearchClient elasticsearch;
    private ElasticsearchProductSearch search;
    private ProductRebuildGateRepository gate;
    private ProductIndexCleanupRepository cleanup;
    private ProductSearchRebuildService rebuild;
    private ProductIndexCleanupWorker cleanupWorker;
    private ProductSearchRebuildRecoveryService recovery;

    @BeforeAll
    static void startDependencies() {
        Properties product = SplitDatabaseContainer.productProperties();
        dataSource = new DriverManagerDataSource(product.getProperty("jdbcUrl"),
                product.getProperty("username"), product.getProperty("password"));
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(product.getProperty("flywayUrl"), product.getProperty("flywayUsername"),
                        product.getProperty("flywayPassword"))
                .locations("classpath:db/migration")
                .load()
                .migrate();
        openSearchClient();
    }

    @BeforeEach
    void resetFixture() throws IOException {
        jdbc.update("DELETE FROM product_index_cleanup_task");
        jdbc.update("DELETE FROM product_index_outbox");
        jdbc.update("DELETE FROM product_projection");
        jdbc.update("UPDATE product_rebuild_gate SET mode='OPEN',intent='NONE',rebuild_index=NULL," +
                "snapshot_sequence_no=0,cutover_sequence_no=0,owner_id=NULL,claim_token=NULL," +
                "lease_until=NULL,generation=1");
        deleteProductIndexes();
        search = new ElasticsearchProductSearch(elasticsearch);
        search.ensureInitializedForAliasRead();
        gate = new ProductRebuildGateRepository(jdbc, new DataSourceTransactionManager(dataSource));
        cleanup = new ProductIndexCleanupRepository(jdbc, new DataSourceTransactionManager(dataSource));
        rebuild = new ProductSearchRebuildService(jdbc, new DataSourceTransactionManager(dataSource),
                search, gate, cleanup);
        cleanupWorker = new ProductIndexCleanupWorker(cleanup, gate, search);
        recovery = new ProductSearchRebuildRecoveryService(gate, cleanup, search);
    }

    @AfterAll
    static void closeDependencies() throws IOException {
        if (transport != null) {
            transport.close();
        }
    }

    @Test
    void rebuildUsesProjectionSnapshotSwitchesAliasesAndPersistsOldIndexCleanup() {
        UUID visible = UUID.randomUUID();
        UUID offSale = UUID.randomUUID();
        projection(visible, 3, "并发编程实战", "线程池", "教材", 18_900, 3, "ON_SALE");
        projection(offSale, 4, "旧版教材", "并发编程", "教材", 9_900, 2, "OFF_SALE");
        outbox(visible, 3);
        outbox(offSale, 4);
        String oldIndex = search.currentReadIndex();

        ProductSearchRebuildService.RebuildResult result = rebuild.rebuildOnce(20);

        assertThat(result.switched()).isTrue();
        assertThat(result.snapshotSequenceNo()).isEqualTo(result.cutoverSequenceNo());
        assertThat(result.targetIndex()).startsWith("campus-product-rebuild-");
        assertThat(search.currentReadIndexes()).containsExactly(result.targetIndex());
        assertThat(search.currentWriteIndexes()).containsExactly(result.targetIndex());
        assertThat(search.search(new ProductSearchPort.SearchRequest("并发编程", "教材", null, null, 0, 20))
                .items())
                .extracting(ProductSearchPort.SearchItem::listingId)
                .containsExactly(visible.toString());
        assertThat(jdbc.queryForObject("SELECT status FROM product_index_cleanup_task WHERE index_name=?",
                String.class, oldIndex)).isEqualTo("NEW");
    }

    @Test
    void indexingGateResumesAfterCutoverAndCleanupNeverDeletesLiveAlias() throws IOException {
        UUID listingId = UUID.randomUUID();
        projection(listingId, 1, "并发编程初版", "并发编程", "教材", 10_000, 2, "ON_SALE");
        outbox(listingId, 1);

        ProductSearchRebuildService.RebuildResult result = rebuild.rebuildOnce(20);
        String liveIndex = result.targetIndex();
        assertThat(gate.isOpenForIndexing()).isTrue();

        projectionUpdate(listingId, 2, "并发编程新版", 1, "ON_SALE");
        outbox(listingId, 2);
        // 重建结束后，正常 worker 仍可以继续领取后续 sequence_no。
        assertThat(gate.isOpenForIndexing()).isTrue();

        cleanup.enqueue(liveIndex, result.generation());
        assertThat(cleanupWorker.cleanupOnce("cleanup-live", 10, Duration.ofSeconds(30))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM product_index_cleanup_task WHERE index_name=?",
                String.class, liveIndex)).isEqualTo("DONE");
        assertThat(elasticsearch.indices().exists(e -> e.index(liveIndex)).value()).isTrue();
    }

    @Test
    void expiredRebuildOwnerCannotFinishAfterRecoveredGenerationTakeover() throws InterruptedException {
        ProductRebuildGateRepository.GateClaim old = gate.acquire("rebuild-old", Duration.ofMillis(1)).orElseThrow();
        Thread.sleep(50);
        assertThat(gate.acquire("rebuild-current", Duration.ofSeconds(30))).isEmpty();
        assertThat(recovery.recoverOnce()).isEqualTo(
                ProductSearchRebuildRecoveryService.RecoveryResult.RELEASED);
        ProductRebuildGateRepository.GateClaim current = gate.acquire("rebuild-current", Duration.ofSeconds(30))
                .orElseThrow();

        assertThat(current.generation()).isGreaterThan(old.generation());
        assertThat(current.claimToken()).isNotEqualTo(old.claimToken());
        assertThat(gate.markCutover(old, 0)).isFalse();
        assertThat(gate.finish(old)).isFalse();
        assertThat(gate.finish(current)).isTrue();
        assertThat(gate.isOpenForIndexing()).isTrue();
    }

    @Test
    void expiredBuildKeepsTargetForRecoveryInsteadOfDiscardingItOnNextAcquire() {
        ProductRebuildGateRepository.GateClaim interrupted = gate.acquire("rebuild-interrupted",
                Duration.ofSeconds(30)).orElseThrow();
        String target = search.newRebuildIndexName();
        assertThat(gate.recordSnapshot(interrupted, target, 0)).isTrue();
        jdbc.update("UPDATE product_rebuild_gate SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=1");

        assertThat(gate.acquire("rebuild-new", Duration.ofSeconds(30))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT rebuild_index FROM product_rebuild_gate WHERE id=1", String.class))
                .isEqualTo(target);
    }

    @Test
    void expiredBuildingTargetIsDurablyEnqueuedBeforeGateReopens() throws IOException {
        ProductRebuildGateRepository.GateClaim old = gate.acquire("rebuild-crashed",
                Duration.ofSeconds(30)).orElseThrow();
        String target = search.newRebuildIndexName();
        assertThat(gate.recordTarget(old, target)).isTrue();
        search.createRebuildIndex(target);
        expireGate();

        assertThat(recovery.recoverOnce()).isEqualTo(
                ProductSearchRebuildRecoveryService.RecoveryResult.RELEASED);
        assertThat(gate.isOpenForIndexing()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM product_index_cleanup_task WHERE index_name=?",
                String.class, target)).isEqualTo("NEW");
        assertThat(cleanupWorker.cleanupOnce("cleanup-recovery", 10, Duration.ofSeconds(30)))
                .isEqualTo(1);
        assertThat(elasticsearch.indices().exists(e -> e.index(target)).value()).isFalse();
    }

    @Test
    void expiredCutoverWithBothAliasesAlreadySwitchedFinishesWithoutDeletingTarget() throws IOException {
        ProductRebuildGateRepository.GateClaim old = gate.acquire("rebuild-cutover",
                Duration.ofSeconds(30)).orElseThrow();
        String target = search.newRebuildIndexName();
        assertThat(gate.recordTarget(old, target)).isTrue();
        search.createRebuildIndex(target);
        assertThat(gate.recordSnapshot(old, target, 0)).isTrue();
        assertThat(gate.markCutover(old, 0)).isTrue();
        Set<String> oldIndexes = search.readAllAliasMembers();
        for (String index : oldIndexes) {
            cleanup.enqueue(index, old.generation());
        }
        search.replaceAliasesWithSingleTarget(target, oldIndexes);
        expireGate();

        assertThat(recovery.recoverOnce()).isEqualTo(
                ProductSearchRebuildRecoveryService.RecoveryResult.FINISHED);
        assertThat(gate.isOpenForIndexing()).isTrue();
        assertThat(search.currentReadIndexes()).containsExactly(target);
        assertThat(search.currentWriteIndexes()).containsExactly(target);
        assertThat(cleanupWorker.cleanupOnce("cleanup-cutover", 10, Duration.ofSeconds(30)))
                .isEqualTo(oldIndexes.size());
        assertThat(elasticsearch.indices().exists(e -> e.index(target)).value()).isTrue();
    }

    @Test
    void renewCommitsWhileConsistentReadOnlySnapshotTransactionRemainsOpen() throws Exception {
        ProductRebuildGateRepository.GateClaim claim = gate.acquire("rebuild-snapshot",
                Duration.ofSeconds(30)).orElseThrow();
        TransactionTemplate snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);

        snapshot.executeWithoutResult(status -> {
            assertThat(gate.renew(claim, Duration.ofMinutes(2))).isTrue();
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(
                         "SELECT lease_until FROM product_rebuild_gate WHERE id=1");
                 var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Timestamp committed = rows.getTimestamp(1);
                assertThat(committed.toInstant()).isAfter(Instant.now().plusSeconds(90));
            } catch (Exception failure) {
                throw new IllegalStateException("独立连接无法观察到已提交的重建续租", failure);
            }
        });
    }

    private static void expireGate() {
        jdbc.update("UPDATE product_rebuild_gate SET lease_until="
                + "TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=1");
    }

    private static void projection(UUID listingId, long version, String title, String description, String category,
                                   long price, int quantity, String status) {
        jdbc.update("""
            INSERT INTO product_projection(listing_id,aggregate_version,title,description,category,
                unit_price_fen,available_quantity,status,updated_at)
            VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
            """, listingId.toString(), version, title, description, category, price, quantity, status);
    }

    private static void projectionUpdate(UUID listingId, long version, String title, int quantity, String status) {
        jdbc.update("UPDATE product_projection SET aggregate_version=?,title=?,available_quantity=?,status=? WHERE listing_id=?",
                version, title, quantity, status, listingId.toString());
    }

    private static void outbox(UUID listingId, long version) {
        jdbc.update("""
            INSERT INTO product_index_outbox(id,listing_id,aggregate_version,status)
            VALUES (?,?,?,'NEW')
            """, UUID.randomUUID().toString(), listingId.toString(), version);
    }

    private static ElasticsearchContainer elasticsearchContainer() {
        DockerImageName image = DockerImageName.parse(ES_IMAGE)
                .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:9.4.5");
        ElasticsearchContainer container = new ElasticsearchContainer(image)
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms128m -Xmx192m")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768L * 1024L * 1024L));
        container.setImage(SMART_CN_IMAGE);
        return container;
    }

    private static void openSearchClient() {
        Rest5Client restClient = Rest5Client
                .builder(URI.create("http://" + ELASTICSEARCH.getHttpHostAddress())).build();
        transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        elasticsearch = new ElasticsearchClient(transport);
    }

    private static void deleteProductIndexes() throws IOException {
        Set<String> indexes = elasticsearch.indices().get(g -> g.index("campus-product-*")).indices().keySet();
        if (!indexes.isEmpty()) {
            elasticsearch.indices().delete(d -> d.index(String.join(",", indexes)));
        }
    }
}
