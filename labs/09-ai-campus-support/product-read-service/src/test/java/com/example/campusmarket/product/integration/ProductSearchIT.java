package com.example.campusmarket.product.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.example.campusmarket.product.search.ProductSearchPort;
import com.example.campusmarket.product.search.ElasticsearchProductSearch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 读侧使用独立 SmartCN Elasticsearch 索引，并以外部版本保护搜索视图。 */
@Testcontainers
class ProductSearchIT {
    private static final String IMAGE_NAME = "campus-market/product-read-elasticsearch:9.4.5-smartcn";
    private static final ImageFromDockerfile SMART_CN_IMAGE = new ImageFromDockerfile(IMAGE_NAME, true)
            .withDockerfile(Path.of("../docker/elasticsearch/Dockerfile"));

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = elasticsearchContainer();

    private static Rest5Client restClient;
    private static ElasticsearchTransport transport;
    private static ElasticsearchClient elasticsearch;
    private ElasticsearchProductSearch search;

    @BeforeAll
    static void openClient() {
        restClient = Rest5Client.builder(URI.create("http://" + ELASTICSEARCH.getHttpHostAddress())).build();
        transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        elasticsearch = new ElasticsearchClient(transport);
    }

    @BeforeEach
    void setUp() throws IOException {
        deleteProductIndexes();
        search = new ElasticsearchProductSearch(elasticsearch);
    }

    @AfterAll
    static void closeClient() throws IOException {
        if (transport != null) {
            transport.close();
        }
        if (restClient != null) {
            restClient.close();
        }
    }

    @Test
    void usesSmartCnAndReturnsOnlyAvailableOnSaleListings() throws IOException {
        String onSale = "read-on-sale";
        String secondOnSale = "read-on-sale-2";
        String offSale = "read-off-sale";
        String soldOut = "read-sold-out";
        String emptyStock = "read-empty-stock";

        search.index(document(onSale, "Java 并发编程实战", "并发编程与线程池", "教材", 18_900, 3, "ON_SALE", 3));
        search.index(document(secondOnSale, "并发编程复习资料", "并发编程练习", "教材", 12_000, 2, "ON_SALE", 2));
        search.index(document(offSale, "并发编程旧版", "Java 并发编程", "教材", 9_900, 2, "OFF_SALE", 4));
        search.index(document(soldOut, "Java 并发编程售罄", "并发编程", "教材", 12_900, 0, "SOLD_OUT", 5));
        search.index(document(emptyStock, "Java 并发编程无库存", "并发编程", "教材", 10_900, 0, "ON_SALE", 6));
        search.refresh();

        AnalyzeResponse analyzed = elasticsearch.indices().analyze(a -> a.index(ProductSearchPort.READ_ALIAS)
                .analyzer("smartcn").text("并发编程实战"));
        assertThat(analyzed.tokens()).extracting(token -> token.token()).contains("并发", "编程");

        ProductSearchPort.SearchPage page = search.search(new ProductSearchPort.SearchRequest(
                "并发编程", "教材", null, null, 0, 20));

        assertThat(page.items()).extracting(ProductSearchPort.SearchItem::listingId)
                .containsExactlyInAnyOrder(onSale, secondOnSale)
                .doesNotContain(offSale, soldOut, emptyStock);
        assertThat(productIndexes()).allMatch(index -> index.startsWith("campus-product-"));
    }

    @Test
    void filtersFenPricesAndPagesWithPitSearchAfter() {
        search.index(document("page-1", "并发教材一", "并发编程", "教材", 18_900, 1, "ON_SALE", 1));
        search.index(document("page-2", "并发教材二", "并发编程", "教材", 12_000, 1, "ON_SALE", 1));
        search.index(document("price-out", "并发教材三", "并发编程", "教材", 30_000, 1, "ON_SALE", 1));
        search.refresh();

        ProductSearchPort.SearchPage pricePage = search.search(new ProductSearchPort.SearchRequest(
                "", "教材", 18_000L, 19_000L, 0, 20));
        assertThat(pricePage.items()).extracting(ProductSearchPort.SearchItem::listingId)
                .containsExactly("page-1");

        ProductSearchPort.SearchPage first = search.search(new ProductSearchPort.SearchRequest(
                "", "教材", null, null, 0, 1));
        assertThat(first.items()).hasSize(1);
        assertThat(first.nextSearchAfter()).isNotBlank();

        ProductSearchPort.SearchPage second = search.search(new ProductSearchPort.SearchRequest(
                "", "教材", null, null, 0, 1, first.nextSearchAfter()));
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().get(0).listingId()).isNotEqualTo(first.items().get(0).listingId());
        assertThat(second.nextSearchAfter()).isNotBlank();

        ProductSearchPort.SearchPage third = search.search(new ProductSearchPort.SearchRequest(
                "", "教材", null, null, 0, 1, second.nextSearchAfter()));
        assertThat(third.items()).hasSize(1);
        assertThat(third.items().get(0).listingId())
                .isNotEqualTo(first.items().get(0).listingId())
                .isNotEqualTo(second.items().get(0).listingId());
        assertThat(third.nextSearchAfter()).isNull();
    }

    @Test
    void externalVersionTombstonePreventsAnOlderListingFromReturning() {
        String listingId = "versioned-listing";
        search.index(document(listingId, "旧标题", "并发编程", "教材", 10_000, 1, "ON_SALE", 1));
        search.index(document(listingId, "最新标题", "并发编程", "教材", 11_000, 1, "ON_SALE", 3));
        search.index(document(listingId, "过期标题", "并发编程", "教材", 9_000, 1, "ON_SALE", 2));
        search.refresh();

        ProductSearchPort.SearchPage latest = search.search(new ProductSearchPort.SearchRequest(
                "并发编程", "教材", null, null, 0, 20));
        assertThat(latest.items()).extracting(ProductSearchPort.SearchItem::listingId).containsExactly(listingId);
        assertThat(latest.items().get(0).title()).isEqualTo("最新标题");
        assertThat(latest.items().get(0).aggregateVersion()).isEqualTo(3);

        search.tombstone(listingId, 4);
        search.index(document(listingId, "迟到旧事件", "并发编程", "教材", 10_000, 1, "ON_SALE", 3));
        search.refresh();

        assertThat(search.search(new ProductSearchPort.SearchRequest(
                "并发编程", "教材", null, null, 0, 20)).items())
                .extracting(ProductSearchPort.SearchItem::listingId)
                .doesNotContain(listingId);
    }

    private static ElasticsearchContainer elasticsearchContainer() {
        DockerImageName compatibleImage = DockerImageName.parse(IMAGE_NAME)
                .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:9.4.5");
        ElasticsearchContainer container = new ElasticsearchContainer(compatibleImage)
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms128m -Xmx192m")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768L * 1024L * 1024L));
        container.setImage(SMART_CN_IMAGE);
        return container;
    }

    private static void deleteProductIndexes() throws IOException {
        if (!elasticsearch.indices().exists(e -> e.index("campus-product-000001")).value()) {
            return;
        }
        elasticsearch.indices().delete(d -> d.index("campus-product-000001"));
    }

    private Set<String> productIndexes() throws IOException {
        return elasticsearch.indices().getAlias(a -> a.name(ProductSearchPort.READ_ALIAS)).aliases().keySet();
    }

    private static ProductSearchPort.ProductDocument document(String id, String title, String description,
                                                              String category, long price, int quantity,
                                                              String status, long version) {
        return new ProductSearchPort.ProductDocument(id, title, description, category, price, quantity, status, version);
    }
}
