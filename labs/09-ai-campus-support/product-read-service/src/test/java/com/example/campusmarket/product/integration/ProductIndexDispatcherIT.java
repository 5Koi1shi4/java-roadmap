package com.example.campusmarket.product.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.example.campusmarket.product.search.ElasticsearchProductSearch;
import com.example.campusmarket.product.search.ProductSearchPort;
import com.example.campusmarket.product.infrastructure.ProductIndexOutboxClaimer;
import com.example.campusmarket.product.infrastructure.ProductIndexCleanupRepository;
import com.example.campusmarket.product.infrastructure.ProductRebuildGateRepository;
import com.example.campusmarket.product.search.ProductIndexDispatcher;
import com.example.campusmarket.product.search.ProductIndexCleanupWorker;
import com.example.campusmarket.testsupport.SplitDatabaseContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 MySQL + SmartCN ES 验证读侧索引待办、fencing 与可重试投递。 */
@Testcontainers
class ProductIndexDispatcherIT {
    private static final String ES_IMAGE = "campus-market/product-read-elasticsearch:9.4.5-smartcn";
    private static final ImageFromDockerfile SMART_CN_IMAGE = new ImageFromDockerfile(ES_IMAGE, true)
            .withDockerfile(Path.of("../docker/elasticsearch/Dockerfile"));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = elasticsearchContainer();

    private static JdbcTemplate jdbc;
    private static DataSource dataSource;
    private static Rest5Client restClient;
    private static ElasticsearchTransport transport;
    private static ElasticsearchClient elasticsearch;
    private ProductIndexOutboxClaimer claimer;
    private ProductIndexDispatcher dispatcher;
    private ElasticsearchProductSearch search;
    private ProductIndexCleanupRepository cleanup;
    private ProductIndexCleanupWorker cleanupWorker;

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
        jdbc.update("""
            UPDATE product_rebuild_gate
            SET mode='OPEN',generation=1,owner_id=NULL,claim_token=NULL,lease_until=NULL
            WHERE id=1
            """);
        deleteInitialSearchIndex();
        search = new ElasticsearchProductSearch(elasticsearch);
        claimer = new ProductIndexOutboxClaimer(jdbc, new DataSourceTransactionManager(dataSource));
        dispatcher = new ProductIndexDispatcher(jdbc, claimer, search);
        ProductRebuildGateRepository gate = new ProductRebuildGateRepository(
                jdbc, new DataSourceTransactionManager(dataSource));
        cleanup = new ProductIndexCleanupRepository(jdbc, new DataSourceTransactionManager(dataSource));
        cleanupWorker = new ProductIndexCleanupWorker(cleanup, gate, search);
    }

    @AfterAll
    static void closeDependencies() throws IOException {
        closeSearchClient();
    }

    @Test
    void indexesOnlyAvailableOnSaleAndTombstonesOtherProjectionStates() {
        UUID visible = UUID.randomUUID();
        UUID offSale = UUID.randomUUID();
        UUID soldOut = UUID.randomUUID();
        UUID noStock = UUID.randomUUID();
        projection(visible, 3, "并发编程实战", "并发编程与线程池", "教材", 18_900, 3, "ON_SALE");
        projection(offSale, 4, "并发编程旧版", "并发编程", "教材", 9_900, 2, "OFF_SALE");
        projection(soldOut, 5, "并发编程售罄", "并发编程", "教材", 12_900, 0, "SOLD_OUT");
        projection(noStock, 6, "并发编程无库存", "并发编程", "教材", 10_900, 0, "ON_SALE");
        outbox(visible, 3);
        outbox(offSale, 4);
        outbox(soldOut, 5);
        outbox(noStock, 6);

        assertThat(dispatcher.dispatchOnce("worker-a", 20, Duration.ofSeconds(30))).isEqualTo(4);
        assertThat(statuses()).containsOnlyKeys(visible.toString(), offSale.toString(), soldOut.toString(), noStock.toString())
                .containsValues("PUBLISHED");
        assertThat(search.search(new ProductSearchPort.SearchRequest("并发编程", "教材", null, null, 0, 20))
                .items())
                .extracting(ProductSearchPort.SearchItem::listingId)
                .containsExactly(visible.toString());
    }

    @Test
    void lateOwnerCannotCompleteAfterLeaseIsFenced() throws InterruptedException {
        UUID listingId = UUID.randomUUID();
        projection(listingId, 1, "并发编程教材", "并发编程", "教材", 10_000, 1, "ON_SALE");
        outbox(listingId, 1);

        ProductIndexOutboxClaimer.Claim late = claimer.claimBatch("worker-late", 1, Duration.ofMillis(1))
                .get(0);
        Thread.sleep(50);
        ProductIndexOutboxClaimer.Claim current = claimer.claimBatch("worker-current", 1, Duration.ofSeconds(30))
                .get(0);

        assertThat(late.claimToken()).isNotEqualTo(current.claimToken());
        assertThat(claimer.markPublished(late)).isFalse();
        assertThat(claimer.markPublished(current)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM product_index_outbox WHERE id=?", String.class,
                current.id())).isEqualTo("PUBLISHED");
    }

    @Test
    void rebuildGateStopsNewClaimsAndFencesAnExistingGeneration() {
        UUID listingId = UUID.randomUUID();
        projection(listingId, 1, "并发编程教材", "并发编程", "教材", 10_000, 1, "ON_SALE");
        outbox(listingId, 1);

        ProductIndexOutboxClaimer.Claim claim = claimer.claimBatch("worker-a", 1,
                Duration.ofSeconds(30)).get(0);
        jdbc.update("""
            UPDATE product_rebuild_gate
            SET mode='REBUILDING',generation=2,owner_id='rebuild-test',claim_token=?,
                lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6))
            WHERE id=1
            """, UUID.randomUUID().toString());

        try {
            assertThat(claimer.claimBatch("worker-b", 1, Duration.ofSeconds(30))).isEmpty();
            assertThat(claimer.isCurrentOpen(claim)).isFalse();
            assertThat(claimer.markRetry(claim, Duration.ofSeconds(1))).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM product_index_outbox WHERE listing_id=?",
                    String.class, listingId.toString())).isEqualTo("NEW");
        } finally {
            jdbc.update("""
                UPDATE product_rebuild_gate
                SET mode='OPEN',generation=3,owner_id=NULL,claim_token=NULL,lease_until=NULL
                WHERE id=1
                """);
        }
    }

    @Test
    void ElasticsearchDisconnectReturnsClaimToRetryAndLaterClearsIt()
            throws IOException, InterruptedException {
        UUID listingId = UUID.randomUUID();
        projection(listingId, 1, "并发编程教材", "并发编程", "教材", 10_000, 1, "ON_SALE");
        outbox(listingId, 1);

        ELASTICSEARCH.stop();
        assertThat(dispatcher.dispatchOnce("worker-a", 1, Duration.ofSeconds(30))).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM product_index_outbox WHERE listing_id=?", String.class,
                listingId.toString())).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM product_index_outbox WHERE listing_id=?", Integer.class,
                listingId.toString())).isEqualTo(1);

        closeSearchClient();
        ELASTICSEARCH.start();
        openSearchClient();
        search = new ElasticsearchProductSearch(elasticsearch);
        dispatcher = new ProductIndexDispatcher(jdbc, claimer, search);

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!"PUBLISHED".equals(jdbc.queryForObject(
                "SELECT status FROM product_index_outbox WHERE listing_id=?", String.class,
                listingId.toString())) && System.nanoTime() < deadline) {
            dispatcher.dispatchOnce("worker-b", 1, Duration.ofSeconds(30));
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM product_index_outbox WHERE listing_id=?", String.class,
                listingId.toString())).isEqualTo("PUBLISHED");
        assertThat(search.search(new ProductSearchPort.SearchRequest("并发编程", "教材", null, null, 0, 20))
                .items())
                .extracting(ProductSearchPort.SearchItem::listingId)
                .containsExactly(listingId.toString());
    }

    @Test
    void newerProjectionTombstoneWinsOverLateOlderIndexWrite() {
        UUID listingId = UUID.randomUUID();
        projection(listingId, 1, "旧标题", "并发编程", "教材", 10_000, 1, "ON_SALE");
        outbox(listingId, 1);
        assertThat(dispatcher.dispatchOnce("worker-a", 1, Duration.ofSeconds(30))).isEqualTo(1);

        jdbc.update("UPDATE product_projection SET aggregate_version=?,status=? WHERE listing_id=?",
                2, "OFF_SALE", listingId.toString());
        outbox(listingId, 2);
        assertThat(dispatcher.dispatchOnce("worker-a", 1, Duration.ofSeconds(30))).isEqualTo(1);

        search.index(new ProductSearchPort.ProductDocument(listingId.toString(), "迟到旧标题", "并发编程", "教材",
                10_000, 1, "ON_SALE", 1));
        search.refresh();
        assertThat(search.search(new ProductSearchPort.SearchRequest("并发编程", "教材", null, null, 0, 20))
                .items())
                .extracting(ProductSearchPort.SearchItem::listingId)
                .doesNotContain(listingId.toString());
    }

    @Test
    void cleanupCanDeleteOldGenerationAfterGateAdvances() throws IOException {
        String oldIndex = search.createRebuildIndex();
        assertThat(search.currentReadIndexes()).doesNotContain(oldIndex);
        assertThat(cleanup.enqueue(oldIndex, 1)).isTrue();

        jdbc.update("""
            UPDATE product_rebuild_gate
            SET mode='OPEN',generation=2,owner_id=NULL,claim_token=NULL,lease_until=NULL
            WHERE id=1
            """);

        assertThat(cleanupWorker.cleanupOnce("cleanup-old-generation", 10,
                Duration.ofSeconds(30))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM product_index_cleanup_task WHERE index_name=?",
                String.class, oldIndex)).isEqualTo("DONE");
        assertThat(elasticsearch.indices().exists(exists -> exists.index(oldIndex)).value()).isFalse();
    }

    @Test
    void permanentEsClientErrorMarksOutboxFailedWithShortDiagnostic() {
        UUID listingId = UUID.randomUUID();
        projection(listingId, 1, "并发编程教材", "并发编程", "教材", 10_000, 1, "ON_SALE");
        outbox(listingId, 1);

        ProductSearchPort permanentFailure = mock(ProductSearchPort.class);
        doThrow(new ProductSearchPort.SearchUnavailableException("商品索引写入失败",
                new IllegalStateException("HTTP/1.1 400 Bad Request: invalid field")))
                .when(permanentFailure).index(any(ProductSearchPort.ProductDocument.class));
        ProductIndexDispatcher failingDispatcher = new ProductIndexDispatcher(jdbc, claimer, permanentFailure);

        assertThat(failingDispatcher.dispatchOnce("worker-permanent", 1, Duration.ofSeconds(30))).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM product_index_outbox WHERE listing_id=?",
                String.class, listingId.toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_class FROM product_index_outbox WHERE listing_id=?",
                String.class, listingId.toString())).isEqualTo("PERMANENT");
        String error = jdbc.queryForObject("SELECT last_error FROM product_index_outbox WHERE listing_id=?",
                String.class, listingId.toString());
        assertThat(error).isNotBlank().contains("400").hasSizeLessThanOrEqualTo(500);
    }

    @Test
    void invalidProjectionMarksOutboxFailedInsteadOfRetryingForever() {
        UUID listingId = UUID.randomUUID();
        projection(listingId, 1, "", "并发编程", "教材", 10_000, 1, "ON_SALE");
        outbox(listingId, 1);

        assertThat(dispatcher.dispatchOnce("worker-invalid-projection", 1,
                Duration.ofSeconds(30))).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM product_index_outbox WHERE listing_id=?",
                String.class, listingId.toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_class FROM product_index_outbox WHERE listing_id=?",
                String.class, listingId.toString())).isEqualTo("PERMANENT");
    }

    @Test
    void transientEsFailureStaysNewAfterMoreThanThreeAttempts() {
        UUID listingId = UUID.randomUUID();
        projection(listingId, 1, "并发编程教材", "并发编程", "教材", 10_000, 1, "ON_SALE");
        outbox(listingId, 1);

        ProductSearchPort transientFailure = mock(ProductSearchPort.class);
        doThrow(new ProductSearchPort.SearchUnavailableException("商品索引写入失败",
                new IllegalStateException("HTTP/1.1 503 Service Unavailable")))
                .when(transientFailure).index(any(ProductSearchPort.ProductDocument.class));
        ProductIndexDispatcher failingDispatcher = new ProductIndexDispatcher(jdbc, claimer, transientFailure);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(failingDispatcher.dispatchOnce("worker-transient", 1,
                    Duration.ofSeconds(30))).isZero();
            jdbc.update("UPDATE product_index_outbox SET available_at=CURRENT_TIMESTAMP(6) WHERE listing_id=?",
                    listingId.toString());
        }

        assertThat(jdbc.queryForObject("SELECT status FROM product_index_outbox WHERE listing_id=?",
                String.class, listingId.toString())).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT failure_class FROM product_index_outbox WHERE listing_id=?",
                String.class, listingId.toString())).isNull();
    }

    @Test
    void cleanupTransientFailureStaysRetryableAfterMoreThanThreeAttempts() {
        String indexName = "campus-product-cleanup-transient-" + UUID.randomUUID().toString().replace("-", "");
        assertThat(cleanup.enqueue(indexName, 1)).isTrue();
        ElasticsearchProductSearch unavailableSearch = mock(ElasticsearchProductSearch.class);
        when(unavailableSearch.readAllAliasMembers())
                .thenThrow(new ElasticsearchProductSearch.SearchUnavailableException(
                        "HTTP/1.1 503 Service Unavailable"));
        ProductIndexCleanupWorker failingWorker = new ProductIndexCleanupWorker(
                cleanup, new ProductRebuildGateRepository(jdbc, new DataSourceTransactionManager(dataSource)),
                unavailableSearch);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(failingWorker.cleanupOnce("cleanup-transient", 1,
                    Duration.ofSeconds(30))).isZero();
            jdbc.update("UPDATE product_index_cleanup_task SET available_at=CURRENT_TIMESTAMP(6) WHERE index_name=?",
                    indexName);
        }

        assertThat(jdbc.queryForObject("SELECT status FROM product_index_cleanup_task WHERE index_name=?",
                String.class, indexName)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT failure_class FROM product_index_cleanup_task WHERE index_name=?",
                String.class, indexName)).isEqualTo("TRANSIENT");
    }

    private static void projection(UUID listingId, long version, String title, String description, String category,
                                   long price, int quantity, String status) {
        jdbc.update("""
            INSERT INTO product_projection(listing_id,aggregate_version,title,description,category,
                unit_price_fen,available_quantity,status,updated_at)
            VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
            """, listingId.toString(), version, title, description, category, price, quantity, status);
    }

    private static void outbox(UUID listingId, long version) {
        jdbc.update("""
            INSERT INTO product_index_outbox(id,listing_id,aggregate_version,status)
            VALUES (?,?,?,'NEW')
            """, UUID.randomUUID().toString(), listingId.toString(), version);
    }

    private static Map<String, String> statuses() {
        return jdbc.query("SELECT listing_id,status FROM product_index_outbox", (result, row) ->
                Map.entry(result.getString("listing_id"), result.getString("status")))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
        restClient = Rest5Client.builder(URI.create("http://" + ELASTICSEARCH.getHttpHostAddress())).build();
        transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        elasticsearch = new ElasticsearchClient(transport);
    }

    private static void closeSearchClient() throws IOException {
        if (transport != null) transport.close();
        if (restClient != null) restClient.close();
        transport = null;
        restClient = null;
        elasticsearch = null;
    }

    private static void deleteInitialSearchIndex() throws IOException {
        if (elasticsearch.indices().exists(exists -> exists.index("campus-product-000001")).value()) {
            elasticsearch.indices().delete(delete -> delete.index("campus-product-000001"));
        }
    }
}
