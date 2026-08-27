package com.example.files.application.access;

/** 下载失败指标允许的固定低基数原因。 */
public enum DownloadFailureReason {
    HEADER_FAILED, STORAGE_READ_FAILED, OBJECT_NOT_FOUND, STREAM_FAILED, ASYNC_ABORTED, OTHER;

    public static DownloadFailureReason fromCode(String code) {
        if (code == null) return OTHER;
        try { return valueOf(code); } catch (IllegalArgumentException ex) { return OTHER; }
    }
}
