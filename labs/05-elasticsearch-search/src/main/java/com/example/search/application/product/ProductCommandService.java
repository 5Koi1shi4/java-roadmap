package com.example.search.application.product;

import com.example.search.application.sync.OutboxEventType;
import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.domain.Product;
import com.example.search.domain.ProductSearchSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class ProductCommandService {
    private final ProductRepository productRepository;
    private final SearchCoordinationRepository coordinationRepository;
    private final SearchOutboxRepository outboxRepository;
    private final Clock clock;

    public ProductCommandService(ProductRepository productRepository,
                                 SearchCoordinationRepository coordinationRepository,
                                 SearchOutboxRepository outboxRepository) {
        this(productRepository, coordinationRepository, outboxRepository, Clock.systemUTC());
    }

    public ProductCommandService(SearchCoordinationRepository coordinationRepository,
                                 ProductRepository productRepository,
                                 SearchOutboxRepository outboxRepository) {
        this(productRepository, coordinationRepository, outboxRepository, Clock.systemUTC());
    }

    public ProductCommandService(ProductRepository productRepository,
                                 SearchCoordinationRepository coordinationRepository,
                                 SearchOutboxRepository outboxRepository, Clock clock) {
        this.productRepository = productRepository;
        this.coordinationRepository = coordinationRepository;
        this.outboxRepository = outboxRepository;
        this.clock = clock;
    }

    public ProductCommandService(SearchCoordinationRepository coordinationRepository,
                                 ProductRepository productRepository,
                                 SearchOutboxRepository outboxRepository, Clock clock) {
        this(productRepository, coordinationRepository, outboxRepository, clock);
    }

    @Transactional
    public ProductView create(CreateProductCommand command) {
        coordinationRepository.lockShared();
        Product product = productRepository.insert(command.details(), clock.instant());
        outboxRepository.append(ProductSearchSnapshot.from(product), OutboxEventType.PRODUCT_UPSERT);
        return ProductView.from(product);
    }

    @Transactional
    public ProductView update(long id, UpdateProductCommand command) {
        coordinationRepository.lockShared();
        int changed = productRepository.updateIfVersionMatches(id, command.expectedVersion(), command.details(), clock.instant());
        if (changed == 0) throw resolveMutationFailure(id, command.expectedVersion());
        Product product = productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
        outboxRepository.append(ProductSearchSnapshot.from(product), OutboxEventType.PRODUCT_UPSERT);
        return ProductView.from(product);
    }

    @Transactional
    public void delete(long id, long expectedVersion) {
        coordinationRepository.lockShared();
        int changed = productRepository.markDeletedIfVersionMatches(id, expectedVersion, clock.instant());
        if (changed == 0) throw resolveMutationFailure(id, expectedVersion);
        Product product = productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
        outboxRepository.append(ProductSearchSnapshot.from(product), OutboxEventType.PRODUCT_DELETE);
    }

    private RuntimeException resolveMutationFailure(long id, long expectedVersion) {
        return productRepository.findById(id).isEmpty()
                ? new ProductNotFoundException(id)
                : new ProductVersionConflictException(id, expectedVersion);
    }
}
