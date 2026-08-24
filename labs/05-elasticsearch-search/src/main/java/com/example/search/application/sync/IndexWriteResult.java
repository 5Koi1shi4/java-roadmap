package com.example.search.application.sync;

public record IndexWriteResult(long productId, long sourceVersion, Outcome outcome, String message) {
    public enum Outcome { APPLIED, SUPERSEDED, RETRYABLE_FAILURE, PERMANENT_FAILURE }

    public IndexWriteResult(long productId, long sourceVersion, Outcome outcome) {
        this(productId, sourceVersion, outcome, null);
    }
}
