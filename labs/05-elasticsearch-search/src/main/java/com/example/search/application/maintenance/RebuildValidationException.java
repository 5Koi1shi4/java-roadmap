package com.example.search.application.maintenance;

public class RebuildValidationException extends RuntimeException {
    private final long differenceCount;

    public RebuildValidationException(String message) {
        this(message, 0);
    }

    public RebuildValidationException(String message, long differenceCount) {
        super(message);
        if (differenceCount < 0) throw new IllegalArgumentException("difference count must not be negative");
        this.differenceCount = differenceCount;
    }

    public long differenceCount() {
        return differenceCount;
    }
}
