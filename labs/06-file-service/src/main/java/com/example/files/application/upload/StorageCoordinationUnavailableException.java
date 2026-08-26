package com.example.files.application.upload;

/** Blob 协调在有界时间内未完成，调用方应稍后重试。 */
public class StorageCoordinationUnavailableException extends RuntimeException {
    public StorageCoordinationUnavailableException(String message) { super(message); }
    public StorageCoordinationUnavailableException(String message, Throwable cause) { super(message, cause); }
}
