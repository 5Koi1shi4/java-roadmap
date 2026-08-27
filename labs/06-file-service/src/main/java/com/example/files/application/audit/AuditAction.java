package com.example.files.application.audit;

/** 文件服务审计动作的固定枚举，避免把自由文本写入动作字段。 */
public enum AuditAction {
    UPLOAD_COMPLETED,
    FILE_ACCESSED,
    ACCESS_GRANTED,
    ACCESS_REVOKED,
    FILE_DELETED,
    DOWNLOAD_AUTHORIZED,
    DOWNLOAD_LINK_ISSUED,
    DOWNLOAD_COMPLETED,
    DOWNLOAD_FAILED
}
