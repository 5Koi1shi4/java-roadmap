package com.example.campusmarket.product.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.example.campusmarket.product.search.ElasticsearchProductSearch;
import com.example.campusmarket.product.search.ProductSearchPort;
import com.example.campusmarket.product.infrastructure.ProductIndexOutboxClaimer;
import com.example.campusmarket.product.search.ProductIndexDispatcher;
import com.example.campusmarket.testsupport.SplitDatabaseContainer;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL + SmartCN ES 验证读侧索引待办、fencing 与可重试投递。 */
@Testcontainers
class ProductIndexDispatcherIT {
    private static final String ES_IMAGE = "campus-market/product-read-elasticsearch:8.18.8-smartcn";
    private static final ImageFromDockerfile SMART_CN_IMAGE = new ImageFromDockerfile(ES_IMAGE, true)
            .withDockerfile(Path.of("../docker/elasticsearch/Dockerfile"));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = elasticsearchContainer();

    private static JdbcTemplate jdbc;
    private static DataSource dataSource;
    private static RestClient restClient;
    private static ElasticsearchTransport transport;
    private static ElasticsearchClient elasticsearch;
    private ProductIndexOutboxClaimer claimer;
    private ProductIndexDispatcher dispatcher;
    private ElasticsearchProductSearch search;

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
                .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:8.18.8");
        ElasticsearchContainer container = new ElasticsearchContainer(image)
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms128m -Xmx192m")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768L * 1024L * 1024L));
        container.setImage(SMART_CN_IMAGE);
        return container;
    }

    private static void openSearchClient() {
        restClient = RestClient.builder(HttpHost.create(ELASTICSEARCH.getHttpHostAddress())).build();
        transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
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
