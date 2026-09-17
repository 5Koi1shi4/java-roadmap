package com.example.campusmarket.supportai.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 交易状态摘要的只读客户端端口；不暴露交易模块内部 Java 类型。 */
@FunctionalInterface
public interface TradeStatusReader {
    StatusView read(String type, UUID id, String bearerToken);

    /** 仅包含客服回答需要的确定性状态字段。 */
    record StatusView(UUID id, String type, String status, Instant createdAt, Instant deadline) {
        public StatusView {
            Objects.requireNonNull(id, "资源 ID 不能为空");
            Objects.requireNonNull(type, "资源类型不能为空");
            Objects.requireNonNull(status, "资源状态不能为空");
            Objects.requireNonNull(createdAt, "创建时间不能为空");
        }
    }
}
