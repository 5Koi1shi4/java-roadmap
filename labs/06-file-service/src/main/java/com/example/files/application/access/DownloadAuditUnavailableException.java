package com.example.files.application.access;

/** Fail-closed marker for an unavailable mandatory download audit sink. */
public final class DownloadAuditUnavailableException extends RuntimeException {
    public DownloadAuditUnavailableException(Throwable cause) { super(cause); }
}
