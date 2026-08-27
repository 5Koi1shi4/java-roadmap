package com.example.files.application.cleanup;

public record CleanupSummary(int claimed, int completed, int retried, int failed) { }
