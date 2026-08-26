package com.example.files.application.upload;

/** 对象存储明确返回对象不存在。 */
public class StorageObjectNotFoundException extends RuntimeException {
    public StorageObjectNotFoundException(String message) { super(message); }
    public StorageObjectNotFoundException(String message, Throwable cause) { super(message, cause); }
}
