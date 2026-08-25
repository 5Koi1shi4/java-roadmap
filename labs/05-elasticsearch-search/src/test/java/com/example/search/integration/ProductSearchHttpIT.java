package com.example.search.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductView;
import com.example.search.application.sync.DispatchSummary;
import com.example.search.application.sync.OutboxDispatcher;
import com.example.search.application.maintenance.SearchIndexBootstrap;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductSearchHttpIT extends SharedSearchContainers {
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProductCommandService products;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired SearchIndexBootstrap bootstrap;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ElasticsearchClient client;
    @Autowired ElasticsearchIndexManager indexes;

    @BeforeEach
    void clean() throws Exception {
        clearTables(dataSource);
        bootstrap.ensureInitialized();
        client.deleteByQuery(d -> d.index("products-write").query(q -> q.matchAll(m -> m)));
        client.indices().refresh(r -> r.index("products-write"));
    }

    @Test
    void searchesSmartCnWithWeightedNameHighlightFiltersSortAndBuckets() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        ProductView nameMatch = createAndSync("Java并发编程实战 " + suffix, "后端教材", "BOOK", "图书", "20.00", "ON_SALE",
                "面向工程师的Java开发教材");
        ProductView descriptionMatch = createAndSync("后端学习套装 " + suffix, "配套教材", "BOOK", "图书", "25.00", "ON_SALE",
                "并发编程实践教材");
        createAndSync("闲置台灯 " + suffix, "阅读灯具", "LIFE", "生活", "20.00", "ON_SALE",
                "并发编程阅读灯");
        createAndSync("下架并发编程 " + suffix, "不可售", "BOOK", "图书", "15.00", "OFF_SHELF",
                "并发编程旧教材");
        createAndSync("并发编程高价套装 " + suffix, "超出价格范围", "BOOK", "图书", "40.00", "ON_SALE",
                "并发编程高级教材");

        URI searchUri = UriComponentsBuilder.fromPath("/api/products/search")
                .queryParam("q", "并发编程")
                .queryParam("categoryCode", "BOOK")
                .queryParam("minPrice", "10")
                .queryParam("maxPrice", "30")
                .queryParam("size", "10")
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
        ResponseEntity<String> response = rest.getForEntity(searchUri, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.at("/items/0/id").asLong()).isEqualTo(nameMatch.id());
        assertThat(body.at("/items/0/name").asText()).contains("Java并发编程实战");
        assertThat(body.at("/items/1/id").asLong()).isEqualTo(descriptionMatch.id());
        assertThat(body.at("/items/0/highlights/name").toString()).contains("<em>", "并发");
        assertThat(response.getBody()).contains("categoryCode", "categoryName");
        assertThat(body.at("/categories/0/code").asText()).isEqualTo("BOOK");
        assertThat(body.at("/categories/0/count").asLong()).isEqualTo(2L);
        assertThat(body.at("/total").asLong()).isEqualTo(2L);
    }

    @Test
    void appliesStablePriceAndNewestSortsAndRejectsInvalidRequests() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        createAndSync("低价 " + suffix, null, "BOOK", "图书", "10.00", "ON_SALE");
        ProductView high = createAndSync("高价 " + suffix, null, "BOOK", "图书", "20.00", "ON_SALE");
        ResponseEntity<String> asc = rest.getForEntity("/api/products/search?sort=priceAsc&size=10", String.class);
        ResponseEntity<String> desc = rest.getForEntity("/api/products/search?sort=priceDesc&size=10", String.class);
        ResponseEntity<String> newest = rest.getForEntity("/api/products/search?sort=newest&size=10", String.class);
        ResponseEntity<String> noKeywordRelevance = rest.getForEntity(
                "/api/products/search?sort=relevance&size=10", String.class);
        assertThat(objectMapper.readTree(asc.getBody()).at("/items/0/name").asText()).startsWith("低价");
        assertThat(objectMapper.readTree(desc.getBody()).at("/items/0/name").asText()).startsWith("高价");
        assertThat(objectMapper.readTree(newest.getBody()).at("/items/0/id").asLong()).isEqualTo(high.id());
        assertThat(objectMapper.readTree(noKeywordRelevance.getBody()).at("/items/0/id").asLong()).isEqualTo(high.id());

        ProductView tieA = createAndSync("同价一 " + suffix, null, "TIE", "同价", "30.00", "ON_SALE");
        ProductView tieB = createAndSync("同价二 " + suffix, null, "TIE", "同价", "30.00", "ON_SALE");
        JsonNode firstPage = objectMapper.readTree(rest.getForObject(
                "/api/products/search?categoryCode=TIE&sort=priceAsc&page=0&size=1", String.class));
        JsonNode secondPage = objectMapper.readTree(rest.getForObject(
                "/api/products/search?categoryCode=TIE&sort=priceAsc&page=1&size=1", String.class));
        assertThat(firstPage.at("/items/0/id").asLong()).isEqualTo(tieA.id());
        assertThat(secondPage.at("/items/0/id").asLong()).isEqualTo(tieB.id());

        ResponseEntity<String> bad = rest.getForEntity("/api/products/search?size=0", String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).doesNotContain("jdbc", "elasticsearch", "Exception", "at ");
        assertThat(rest.getForEntity("/api/products/search?page=x", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/products/search?minPrice=x", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void productMutationsUseUtf8AndReturnSafe404409AndUnknownField400() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        HttpEntity<String> create = new HttpEntity<>("{\"name\":\"中文商品\",\"description\":\"描述\",\"categoryCode\":\"BOOK\",\"categoryName\":\"图书\",\"price\":\"10.00\",\"status\":\"ON_SALE\"}", headers);
        ResponseEntity<String> created = rest.postForEntity("/api/products", create, String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = objectMapper.readTree(created.getBody()).get("id").asLong();

        ResponseEntity<String> conflict = rest.exchange("/api/products/" + id, HttpMethod.PUT,
                new HttpEntity<>("{\"expectedVersion\":99,\"name\":\"改名\",\"description\":\"描述\",\"categoryCode\":\"BOOK\",\"categoryName\":\"图书\",\"price\":\"10.00\",\"status\":\"ON_SALE\"}", headers), String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> updated = rest.exchange("/api/products/" + id, HttpMethod.PUT,
                new HttpEntity<>("{\"expectedVersion\":1,\"name\":\"改名\",\"description\":\"描述\",\"categoryCode\":\"BOOK\",\"categoryName\":\"图书\",\"price\":\"10.00\",\"status\":\"ON_SALE\"}", headers), String.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(updated.getBody()).get("version").asLong()).isEqualTo(2L);
        dispatcher.dispatchOnce();
        indexes.refresh("products-write");
        assertThat(objectMapper.readTree(rest.getForObject("/api/products/search?q=改名", String.class))
                .at("/total").asLong()).isEqualTo(1L);

        assertThat(rest.exchange("/api/products/" + id, HttpMethod.DELETE, HttpEntity.EMPTY, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/products/not-a-number", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Void> deleted = rest.exchange("/api/products/" + id + "?expectedVersion=2",
                HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        dispatcher.dispatchOnce();
        indexes.refresh("products-write");
        assertThat(objectMapper.readTree(rest.getForObject("/api/products/search?q=改名", String.class))
                .at("/total").asLong()).isZero();

        ResponseEntity<String> missing = rest.getForEntity("/api/products/999999999", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> unknown = rest.postForEntity("/api/products",
                new HttpEntity<>("{\"name\":\"x\",\"description\":\"d\",\"categoryCode\":\"B\",\"categoryName\":\"书\",\"price\":\"1.00\",\"status\":\"ON_SALE\",\"unexpected\":true}", headers), String.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody()).doesNotContain("unexpected", "stackTrace");
    }

    @Test
    void malformedSearchDocumentReturnsSafe503() throws Exception {
        ProductView product = createAndSync("异常文档", null, "BOOK", "图书", "10.00", "ON_SALE");
        client.update(u -> u.index("products-write").id(Long.toString(product.id()))
                .script(s -> s.source("ctx._source.remove('productId')")), Map.class);
        indexes.refresh("products-write");

        ResponseEntity<String> response = rest.getForEntity("/api/products/search?q=异常文档", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("SEARCH_UNAVAILABLE");
        assertThat(response.getBody()).doesNotContain("productId", "elasticsearch", "Exception", "at ");
    }

    private ProductView createAndSync(String name, String subtitle, String categoryCode, String categoryName,
                                      String price, String status) {
        return createAndSync(name, subtitle, categoryCode, categoryName, price, status, "商品描述 " + name);
    }

    private ProductView createAndSync(String name, String subtitle, String categoryCode, String categoryName,
                                      String price, String status, String description) {
        ProductView product = products.create(new CreateProductCommand(new ProductDetails(name, subtitle,
                description, categoryCode, categoryName, new BigDecimal(price), ProductStatus.valueOf(status))));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(new DispatchSummary(1, 1, 0, 0, 0));
        indexes.refresh("products-write");
        return product;
    }
}
