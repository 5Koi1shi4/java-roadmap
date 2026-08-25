package com.example.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.example.search.application.search.ProductSearchCriteria;
import com.example.search.application.search.SearchUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class ElasticsearchProductSearchGatewayTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void wrapsInvalidStatusFromElasticsearchDocumentAsUnavailable() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        SearchResponse<Map> response = SearchResponse.of(s -> s
                .took(1).timedOut(false)
                .shards(sh -> sh.total(1).successful(1).failed(0))
                .hits(h -> h.total(t -> t.value(1).relation(co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq))
                        .hits(hit -> hit.index("products-vtest").id("1").source(Map.ofEntries(
                                Map.entry("productId", 1L), Map.entry("name", "bad"),
                                Map.entry("description", "bad document"), Map.entry("categoryCode", "BOOK"),
                                Map.entry("categoryName", "图书"), Map.entry("price", 10.0),
                                Map.entry("status", "CORRUPT"), Map.entry("sourceVersion", 1L),
                                Map.entry("createdAt", "2026-01-01T00:00:00Z"),
                                Map.entry("updatedAt", "2026-01-01T00:00:00Z"))))));
        doReturn(response).when(client).search(
                org.mockito.ArgumentMatchers.<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
                eq(Map.class));

        ElasticsearchProductSearchGateway gateway = new ElasticsearchProductSearchGateway(client);

        assertThatThrownBy(() -> gateway.search(ProductSearchCriteria.of(
                "bad", null, null, null, 0, 10, "relevance")))
                .isInstanceOf(SearchUnavailableException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
