package com.example.files.api.security;

/** 请求没有通过当前身份适配器认证。 */
public final class RequesterUnauthenticatedException extends RuntimeException {
    public RequesterUnauthenticatedException() {
        super("requester is not authenticated");
    }
}
