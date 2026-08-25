package com.example.search.application.search;

public enum ProductSort {
    RELEVANCE("relevance"), PRICE_ASC("priceAsc"), PRICE_DESC("priceDesc"), NEWEST("newest");

    private final String wireValue;

    ProductSort(String wireValue) { this.wireValue = wireValue; }

    public String wireValue() { return wireValue; }

    public static ProductSort parse(String value) {
        if (value == null || value.isBlank()) return RELEVANCE;
        for (ProductSort sort : values()) if (sort.wireValue.equals(value)) return sort;
        throw new IllegalArgumentException("unsupported sort");
    }
}
