package com.example.search.application.maintenance;

/** The physical index selected by the read and write aliases. */
public record AliasTargets(String read, String write) {
    public static final String READ_ALIAS = "products-read";
    public static final String WRITE_ALIAS = "products-write";

    public AliasTargets {
        if (read == null && write != null || read != null && write == null) {
            throw new IllegalArgumentException("read and write aliases must be present together");
        }
    }

    public String readIndex() { return read; }
    public String writeIndex() { return write; }
    public String readTarget() { return read; }
    public String writeTarget() { return write; }
    public String readAliasTarget() { return read; }
    public String writeAliasTarget() { return write; }
    public boolean isConfigured() { return read != null; }
}
