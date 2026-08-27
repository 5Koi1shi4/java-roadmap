package com.example.files.api.security;

/** 已完成协议认证的请求主体；应用层只接收正数用户 ID。 */
public record RequesterIdentity(long userId) {
    public RequesterIdentity {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
    }
}
