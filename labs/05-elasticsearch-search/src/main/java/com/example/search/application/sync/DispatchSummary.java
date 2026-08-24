package com.example.search.application.sync;

/** Counts the result of one bounded outbox dispatch pass. */
public record DispatchSummary(int claimed, int completed, int rescheduled, int failed, int fenced) {
    public DispatchSummary {
        if (claimed < 0 || completed < 0 || rescheduled < 0 || failed < 0 || fenced < 0) {
            throw new IllegalArgumentException("dispatch counts must not be negative");
        }
    }
}
