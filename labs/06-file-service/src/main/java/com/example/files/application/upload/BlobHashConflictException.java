package com.example.files.application.upload;

/** 仅表示内容哈希唯一键竞争；调用方必须在当前事务结束后重试。 */
public final class BlobHashConflictException extends RuntimeException {
    public BlobHashConflictException(Throwable cause) { super(cause); }
}
