package com.example.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.example.search.application.sync.IndexMutation;
import com.example.search.application.sync.IndexWriteResult;
import com.example.search.application.sync.SearchIndexWriter;
import com.example.search.application.sync.SyncFailureClassifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ElasticsearchSearchIndexWriter implements SearchIndexWriter {
    private final ElasticsearchClient client;
    private final SyncFailureClassifier failureClassifier;

    public ElasticsearchSearchIndexWriter(ElasticsearchClient client) {
        this(client, new SyncFailureClassifier());
    }

    public ElasticsearchSearchIndexWriter(ElasticsearchClient client, SyncFailureClassifier failureClassifier) {
        this.client = client;
        this.failureClassifier = failureClassifier;
    }

    @Override
    public List<IndexWriteResult> bulkWrite(String target, List<IndexMutation> mutations) {
        if (target == null || target.isBlank()) throw new IllegalArgumentException("target is required");
        if (mutations == null) throw new IllegalArgumentException("mutations are required");
        if (mutations.isEmpty()) return List.of();
        List<BulkOperation> operations = mutations.stream().map(mutation ->
                new BulkOperation.Builder().index(i -> i.index(target)
                        .id(Long.toString(mutation.productId()))
                        .version(mutation.sourceVersion())
                        .versionType(VersionType.ExternalGte)
                        .document(mutation.document())).build()).toList();
        try {
            BulkResponse response = client.bulk(b -> b.operations(operations));
            List<IndexWriteResult> results = new ArrayList<>(mutations.size());
            for (int i = 0; i < mutations.size(); i++) {
                results.add(mapItem(mutations.get(i), response.items().get(i)));
            }
            return results;
        } catch (Exception e) {
            IndexWriteResult.Outcome outcome = failureClassifier.classify(e);
            String reason = failureClassifier.reason(e);
            return mutations.stream().map(m -> new IndexWriteResult(m.productId(), m.sourceVersion(), outcome,
                    reason)).toList();
        }
    }

    static IndexWriteResult mapItem(IndexMutation mutation, BulkResponseItem item) {
        if (item.status() >= 200 && item.status() < 300) {
            return new IndexWriteResult(mutation.productId(), mutation.sourceVersion(),
                    IndexWriteResult.Outcome.APPLIED, item.result());
        }
        String type = item.error() == null ? "" : item.error().type();
        if ("version_conflict_engine_exception".equals(type) || item.status() == 409) {
            return new IndexWriteResult(mutation.productId(), mutation.sourceVersion(),
                    IndexWriteResult.Outcome.SUPERSEDED, item.error().reason());
        }
        IndexWriteResult.Outcome outcome = retryableStatus(item.status())
                ? IndexWriteResult.Outcome.RETRYABLE_FAILURE
                : IndexWriteResult.Outcome.PERMANENT_FAILURE;
        return new IndexWriteResult(mutation.productId(), mutation.sourceVersion(), outcome,
                item.error() == null ? "HTTP " + item.status() : item.error().reason());
    }

    private static boolean retryableStatus(int status) {
        return status == 429 || status >= 500;
    }
}
