package com.example.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import com.example.search.application.sync.SearchIndexWriter;
import com.example.search.application.sync.SyncFailureClassifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.example.search.application.sync.IndexMutation;
import com.example.search.application.sync.IndexWriteResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElasticsearchSearchIndexWriterTest {
    private static final IndexMutation MUTATION = IndexMutation.upsert(7L, 3L,
            Map.of("productId", 7L, "sourceVersion", 3L, "status", "ON_SALE"));

    @Test
    void mapsSuccessfulItemToApplied() {
        assertThat(ElasticsearchSearchIndexWriter.mapItem(MUTATION, item(200, null)))
                .extracting(IndexWriteResult::outcome)
                .isEqualTo(IndexWriteResult.Outcome.APPLIED);
    }

    @Test
    void mapsVersionConflictToSuperseded() {
        assertThat(ElasticsearchSearchIndexWriter.mapItem(MUTATION,
                item(409, ErrorCause.of(e -> e.type("version_conflict_engine_exception").reason("old")))))
                .extracting(IndexWriteResult::outcome)
                .isEqualTo(IndexWriteResult.Outcome.SUPERSEDED);
    }

    @Test
    void mapsRateLimitAndServerErrorsToRetryableFailure() {
        assertThat(ElasticsearchSearchIndexWriter.mapItem(MUTATION, item(429, null)).outcome())
                .isEqualTo(IndexWriteResult.Outcome.RETRYABLE_FAILURE);
        assertThat(ElasticsearchSearchIndexWriter.mapItem(MUTATION, item(503, null)).outcome())
                .isEqualTo(IndexWriteResult.Outcome.RETRYABLE_FAILURE);
    }

    @Test
    void mapsParsingErrorsToPermanentFailure() {
        assertThat(ElasticsearchSearchIndexWriter.mapItem(MUTATION,
                item(400, ErrorCause.of(e -> e.type("mapper_parsing_exception").reason("bad"))))
                .outcome()).isEqualTo(IndexWriteResult.Outcome.PERMANENT_FAILURE);
    }

    @Test
    void classifiesSerializationIOExceptionAsPermanentInsteadOfRetryable() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.bulk(any(BulkRequest.class))).thenThrow(new JsonProcessingException("serialization failed") { });
        SearchIndexWriter writer = new ElasticsearchSearchIndexWriter(client, new SyncFailureClassifier());

        assertThat(writer.bulkWrite("products-write", java.util.List.of(MUTATION)))
                .singleElement().extracting(IndexWriteResult::outcome)
                .isEqualTo(IndexWriteResult.Outcome.PERMANENT_FAILURE);
    }

    private static BulkResponseItem item(int status, ErrorCause cause) {
        return BulkResponseItem.of(i -> {
            i.operationType(OperationType.Index).status(status).id("7").index("products-vtest");
            if (cause != null) i.error(cause);
            return i;
        });
    }
}
