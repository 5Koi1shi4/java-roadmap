package com.example.search.application.sync;

import com.example.search.domain.ProductSearchSnapshot;

public interface SearchOutboxRepository {
    void append(ProductSearchSnapshot snapshot, OutboxEventType eventType);
}
