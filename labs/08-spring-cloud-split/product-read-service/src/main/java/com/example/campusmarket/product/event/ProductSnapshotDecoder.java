package com.example.campusmarket.product.event;

import java.io.IOException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/** 只在消息协议入口解码；未知字段、类型和值均快速失败。 */
@Component
public final class ProductSnapshotDecoder {
    private static final int MAX_EVENT_BYTES = 64 * 1024;

    private final JsonMapper mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .addModule(new JavaTimeModule())
        .build();

    public ProductSnapshotEvent decode(byte[] jsonUtf8) {
        if (jsonUtf8 == null || jsonUtf8.length == 0 || jsonUtf8.length > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("商品事件长度无效");
        }
        try {
            return mapper.readValue(jsonUtf8, ProductSnapshotEvent.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("商品快照事件无效", exception);
        }
    }
}
