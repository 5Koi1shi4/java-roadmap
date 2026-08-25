package com.example.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.example.search.application.maintenance.IndexedProductVersion;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fixed PIT/search_after scanner used by final rebuild validation. */
@Component
public class ElasticsearchIndexScanner {
    private static final String PIT_KEEP_ALIVE = "1m";
    private final ElasticsearchClient client;

    public ElasticsearchIndexScanner(ElasticsearchClient client) { this.client = client; }

    public List<IndexedProductVersion> scanAllVersions(String index, int batchSize) {
        if (index == null || index.isBlank() || batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("invalid scan request");
        }
        String pit = null;
        try {
            pit = client.openPointInTime(o -> o.index(index).keepAlive(t -> t.time(PIT_KEEP_ALIVE))).id();
            List<IndexedProductVersion> values = new ArrayList<>();
            List<FieldValue> after = null;
            while (true) {
                final String currentPit = pit;
                final List<FieldValue> currentAfter = after;
                SearchResponse<Map> response = client.search(s -> {
                    s.size(batchSize).pit(p -> p.id(currentPit).keepAlive(t -> t.time(PIT_KEEP_ALIVE)))
                            .query(q -> q.matchAll(m -> m))
                            .sort(sort -> sort.field(f -> f.field("productId").order(SortOrder.Asc)))
                            .sort(sort -> sort.field(f -> f.field("_shard_doc").order(SortOrder.Asc)));
                    if (currentAfter != null && !currentAfter.isEmpty()) s.searchAfter(currentAfter);
                    return s;
                }, Map.class);
                if (response.hits().hits().isEmpty()) break;
                for (var hit : response.hits().hits()) {
                    Map source = hit.source();
                    if (source == null) throw new IllegalStateException("indexed product source is missing");
                    Object id = source.get("productId");
                    Object version = source.get("sourceVersion");
                    Object status = source.get("status");
                    if (!(id instanceof Number) || !(version instanceof Number) || status == null) {
                        throw new IllegalStateException("indexed product tuple is incomplete");
                    }
                    values.add(new IndexedProductVersion(((Number) id).longValue(), ((Number) version).longValue(), status.toString()));
                }
                List<FieldValue> sort = response.hits().hits().get(response.hits().hits().size() - 1).sort();
                if (sort == null || sort.isEmpty()) throw new IllegalStateException("PIT sort value is missing");
                after = sort;
            }
            return List.copyOf(values);
        } catch (IOException e) {
            throw new IllegalStateException("could not scan search index " + index, e);
        } finally {
            if (pit != null) {
                String closePit = pit;
                try { client.closePointInTime(c -> c.id(closePit)); }
                catch (IOException ignored) { }
            }
        }
    }
}
