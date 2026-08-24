package com.example.search.application.sync;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** Classifies failures that happen outside an individual bulk item. */
public final class SyncFailureClassifier {
    public IndexWriteResult.Outcome classify(Throwable failure) {
        if (failure == null) {
            return IndexWriteResult.Outcome.PERMANENT_FAILURE;
        }
        boolean network = false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (isPermanent(current)) {
                return IndexWriteResult.Outcome.PERMANENT_FAILURE;
            }
            if (current instanceof ElasticsearchException elasticsearchException) {
                int status = elasticsearchException.status();
                return status == 429 || status >= 500
                        ? IndexWriteResult.Outcome.RETRYABLE_FAILURE
                        : IndexWriteResult.Outcome.PERMANENT_FAILURE;
            }
            if (isNetwork(current)) {
                network = true;
            }
            Integer status = statusInMessage(current.getMessage());
            if (status != null) {
                return status == 429 || status >= 500
                        ? IndexWriteResult.Outcome.RETRYABLE_FAILURE
                        : IndexWriteResult.Outcome.PERMANENT_FAILURE;
            }
        }
        return network ? IndexWriteResult.Outcome.RETRYABLE_FAILURE
                : IndexWriteResult.Outcome.PERMANENT_FAILURE;
    }

    public String reason(Throwable failure) {
        if (failure == null) {
            return "unknown synchronization failure";
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return sanitizeReason(message);
    }

    public String sanitizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String normalized = reason.replace("\r", "").replace("\n", "");
        return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
    }

    private static boolean isPermanent(Throwable failure) {
        String name = failure.getClass().getName().toLowerCase(Locale.ROOT);
        String message = failure.getMessage();
        String lowerMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return failure instanceof IllegalArgumentException
                || failure instanceof URISyntaxException
                || name.contains("jsonprocess")
                || name.contains("serialization")
                || lowerMessage.contains("mapper_parsing")
                || lowerMessage.contains("strict_dynamic_mapping")
                || lowerMessage.contains("illegal snapshot")
                || lowerMessage.contains("mapping_exception");
    }

    private static boolean isNetwork(Throwable failure) {
        return failure instanceof IOException
                || failure instanceof ConnectException
                || failure instanceof UnknownHostException
                || failure instanceof SocketException
                || failure instanceof SocketTimeoutException
                || failure instanceof HttpTimeoutException
                || failure instanceof TimeoutException
                || failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException
                || hasNetworkWord(failure.getMessage());
    }

    private static boolean hasNetworkWord(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("timeout") || lower.contains("connection reset")
                || lower.contains("connection refused") || lower.contains("connect error")
                || lower.contains("temporarily unavailable");
    }

    private static Integer statusInMessage(String message) {
        if (message == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b([245]\\d\\d)\\b").matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
