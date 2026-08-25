package com.example.search.application.search;

import com.example.search.application.sync.SearchSyncMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
public class ProductSearchService {
    private final ProductSearchGateway gateway;
    private final SearchSyncMetrics metrics;

    public ProductSearchService(ProductSearchGateway gateway) {
        this(gateway, SearchSyncMetrics.noop());
    }

    @Autowired
    public ProductSearchService(ProductSearchGateway gateway, ObjectProvider<SearchSyncMetrics> metrics) {
        this(gateway, metrics.getIfAvailable(SearchSyncMetrics::noop));
    }

    public ProductSearchService(ProductSearchGateway gateway, SearchSyncMetrics metrics) {
        this.gateway = Objects.requireNonNull(gateway, "gateway is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
    }

    public ProductSearchResult search(ProductSearchCriteria criteria) {
        Instant started = Instant.now();
        try {
            ProductSearchResult result = gateway.search(criteria);
            metrics.recordQuery(true, Duration.between(started, Instant.now()));
            return result;
        } catch (RuntimeException failure) {
            metrics.recordQuery(false, Duration.between(started, Instant.now()));
            throw failure;
        }
    }
}
