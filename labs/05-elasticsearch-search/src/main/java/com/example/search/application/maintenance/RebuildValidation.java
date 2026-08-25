package com.example.search.application.maintenance;

import java.util.List;

/** Result of comparing the product source of truth with an index scan. */
public record RebuildValidation(boolean consistent, long differenceCount,
                                List<IndexedProductVersion> expected, List<IndexedProductVersion> actual) {
    public RebuildValidation {
        if (differenceCount < 0 || expected == null || actual == null) {
            throw new IllegalArgumentException("invalid rebuild validation");
        }
        expected = List.copyOf(expected);
        actual = List.copyOf(actual);
    }

    public static RebuildValidation consistent(List<IndexedProductVersion> values) {
        return new RebuildValidation(true, 0, values, values);
    }
}
