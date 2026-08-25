package com.example.search.application.maintenance;

import java.util.List;

/** A bounded, operator-friendly report of source/index version drift. */
public record ConsistencyReport(long missingCount, long staleCount, long orphanCount,
                                List<Long> missingSample, List<Long> staleSample, List<Long> orphanSample) {
    public ConsistencyReport {
        if (missingCount < 0 || staleCount < 0 || orphanCount < 0) {
            throw new IllegalArgumentException("difference counts must not be negative");
        }
        missingSample = bounded(missingSample);
        staleSample = bounded(staleSample);
        orphanSample = bounded(orphanSample);
    }

    public long differenceCount() { return missingCount + staleCount + orphanCount; }
    public List<Long> missingProductIds() { return missingSample; }
    public List<Long> staleProductIds() { return staleSample; }
    public List<Long> orphanProductIds() { return orphanSample; }

    private static List<Long> bounded(List<Long> values) {
        if (values == null) throw new IllegalArgumentException("samples are required");
        if (values.size() > 20) throw new IllegalArgumentException("samples must contain at most 20 ids");
        return List.copyOf(values);
    }
}
