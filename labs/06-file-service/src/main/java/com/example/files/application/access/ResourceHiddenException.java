package com.example.files.application.access;

/** 已认证调用者看不到的资源统一使用该异常，避免存在性侧信号。 */
public final class ResourceHiddenException extends RuntimeException {
    public static final String CODE = "RESOURCE_NOT_FOUND";
    public static final String MESSAGE = "文件不存在";

    public ResourceHiddenException() { super(MESSAGE); }
}
