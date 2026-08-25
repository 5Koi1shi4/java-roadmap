package com.example.search.application.search;

public record CategoryBucket(String code, String name, long count) {
    public CategoryBucket {
        if (code == null || code.isBlank() || name == null || name.isBlank() || count < 0) {
            throw new IllegalArgumentException("invalid category bucket");
        }
    }
}
