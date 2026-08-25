package com.example.search.application.maintenance;

import com.example.search.application.product.ProductRepository;
import com.example.search.domain.Product;
import com.example.search.domain.ProductSearchSnapshot;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexScanner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Compares all product id/version/status tuples to a fixed PIT scan. */
@Component
public class RebuildValidator {
    private final ProductRepository products;
    private final ElasticsearchIndexScanner scanner;

    public RebuildValidator(ProductRepository products, ElasticsearchIndexScanner scanner) {
        this.products = products;
        this.scanner = scanner;
    }

    public RebuildValidation validate(String targetIndex, long finalWatermark) {
        if (targetIndex == null || targetIndex.isBlank() || finalWatermark < 0) {
            throw new IllegalArgumentException("invalid validation request");
        }
        List<IndexedProductVersion> expected = new ArrayList<>();
        long lastId = 0;
        List<Product> page;
        do {
            page = products.findPageAfter(lastId, 500);
            for (Product product : page) {
                expected.add(new IndexedProductVersion(product.id(), product.version(), product.details().status().name()));
                lastId = product.id();
            }
        } while (!page.isEmpty());
        expected.sort(Comparator.comparingLong(IndexedProductVersion::productId));
        List<IndexedProductVersion> actual = scanner.scanAllVersions(targetIndex, 500);
        actual = new ArrayList<>(actual);
        actual.sort(Comparator.comparingLong(IndexedProductVersion::productId));
        long differences = 0;
        int max = Math.max(expected.size(), actual.size());
        for (int i = 0; i < max; i++) {
            if (i >= expected.size() || i >= actual.size() || !expected.get(i).equals(actual.get(i))) differences++;
        }
        return new RebuildValidation(differences == 0, differences, expected, actual);
    }
}
