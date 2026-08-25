package com.example.search.application.maintenance;

import com.example.search.application.product.ProductNotFoundException;
import com.example.search.application.product.ProductRepository;
import com.example.search.application.sync.OutboxEventType;
import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.domain.ProductSearchSnapshot;
import com.example.search.domain.Product;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexScanner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Compares the current MySQL product view with one fixed PIT index scan. */
@Service
public class SearchConsistencyService {
    private static final int PAGE_SIZE = 500;

    private final ProductRepository products;
    private final ElasticsearchIndexManager indexes;
    private final ElasticsearchIndexScanner scanner;
    private final SearchCoordinationRepository coordination;
    private final SearchOutboxRepository outbox;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public SearchConsistencyService(ProductRepository products, ElasticsearchIndexManager indexes,
                                    ElasticsearchIndexScanner scanner, SearchCoordinationRepository coordination,
                                    SearchOutboxRepository outbox, PlatformTransactionManager transactionManager) {
        this.products = Objects.requireNonNull(products, "products is required");
        this.indexes = Objects.requireNonNull(indexes, "indexes is required");
        this.scanner = Objects.requireNonNull(scanner, "scanner is required");
        this.coordination = Objects.requireNonNull(coordination, "coordination is required");
        this.outbox = Objects.requireNonNull(outbox, "outbox is required");
        this.transactionManager = transactionManager;
    }

    public SearchConsistencyService(ProductRepository products, ElasticsearchIndexManager indexes,
                                    ElasticsearchIndexScanner scanner, SearchCoordinationRepository coordination,
                                    SearchOutboxRepository outbox) {
        this(products, indexes, scanner, coordination, outbox, null);
    }

    public ConsistencyReport check() {
        return readTx(() -> {
            coordination.lockShared();
            AliasTargets aliases = indexes.aliasTargets();
            if (!aliases.isConfigured()) throw new IllegalStateException("search aliases are not configured");
            Map<Long, IndexedProductVersion> expected = new HashMap<>();
            long lastId = 0;
            List<Product> page;
            do {
                page = products.findPageAfter(lastId, PAGE_SIZE);
                for (Product product : page) {
                    lastId = product.id();
                    // Deleted products are represented by an absent document in the search index.
                    if (product.details().status().name().equals("DELETED")) continue;
                    expected.put(product.id(), new IndexedProductVersion(product.id(), product.version(),
                            product.details().status().name()));
                }
            } while (!page.isEmpty());

            Map<Long, IndexedProductVersion> actual = new HashMap<>();
            for (IndexedProductVersion value : scanner.scanAllVersions(aliases.read(), PAGE_SIZE)) {
                actual.put(value.productId(), value);
            }
            List<Long> missing = new ArrayList<>();
            List<Long> stale = new ArrayList<>();
            List<Long> orphan = new ArrayList<>();
            expected.keySet().stream().sorted().forEach(id -> {
                IndexedProductVersion indexed = actual.remove(id);
                if (indexed == null) missing.add(id);
                else if (!expected.get(id).equals(indexed)) stale.add(id);
            });
            actual.keySet().stream().sorted().forEach(orphan::add);
            return new ConsistencyReport(missing.size(), stale.size(), orphan.size(),
                    firstTwenty(missing), firstTwenty(stale), firstTwenty(orphan));
        });
    }

    public void repairProduct(long productId) {
        if (productId <= 0) throw new IllegalArgumentException("productId must be positive");
        writeTx(() -> {
            coordination.lockShared();
            Product product = products.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
            ProductSearchSnapshot snapshot = ProductSearchSnapshot.from(product);
            OutboxEventType type = product.details().status().name().equals("DELETED")
                    ? OutboxEventType.PRODUCT_DELETE : OutboxEventType.PRODUCT_UPSERT;
            outbox.repair(snapshot, type);
            return null;
        });
    }

    public void retryFailed(UUID eventId) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        writeTx(() -> {
            coordination.lockShared();
            if (!outbox.retryFailed(eventId)) {
                throw new IllegalArgumentException("event is not FAILED or does not exist");
            }
            return null;
        });
    }

    private <T> T readTx(Supplier<T> action) {
        if (transactionManager == null) return action.get();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        return tx.execute(status -> action.get());
    }

    private <T> T writeTx(Supplier<T> action) {
        if (transactionManager == null) return action.get();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(30);
        return tx.execute(status -> action.get());
    }

    private static List<Long> firstTwenty(List<Long> values) {
        return values.size() <= 20 ? List.copyOf(values) : List.copyOf(values.subList(0, 20));
    }
}
