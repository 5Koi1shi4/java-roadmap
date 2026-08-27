package com.example.files.application.access;

/** 强制下载审计不可用时的 fail-close 标记。 */
public final class DownloadAuditUnavailableException extends RuntimeException {
    public DownloadAuditUnavailableException(Throwable cause) { super(cause); }
}
