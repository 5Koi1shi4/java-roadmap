package com.example.search.application.search;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class ProductSearchService {
    private final ProductSearchGateway gateway;

    public ProductSearchService(ProductSearchGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway is required");
    }

    public ProductSearchResult search(ProductSearchCriteria criteria) {
        return gateway.search(criteria);
    }
}
